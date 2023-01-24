package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.model.traits.PatternTrait
import software.amazon.smithy.model.traits.RangeTrait
import software.amazon.smithy.model.traits.RequiredTrait
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.traits.UniqueItemsTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.server.smithy.hasConstraintTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*
import kotlin.streams.toList

object RefactorConstrainedMemberType {
    // Todo: is there a case when we might need to have a List<Shape> as new types that are defined?
    data class MemberTransformation(val newShape : Shape, val memberToChange: MemberShape, val traitsToKeep : List<Trait>)

    // TODO: there must be some way to get these IDs?
    private val constraintTraitIds = setOf(
        LengthTrait::ID,
        PatternTrait::ID,
        RangeTrait::ID,
        UniqueItemsTrait::ID,
        EnumTrait::ID,
        RequiredTrait::ID,
    )

    /// Returns members which have constraint traits applied to them
    private fun StructureShape.constrainedMembers() =
        this.allMembers.filter { (_, memberField) ->
            memberField.hasConstraintTrait()
        }

    private fun transformConstrainedMembers(model : Model, structureShapeId : ShapeId) : List<Optional<MemberTransformation>> {
        val structure = model.expectShape(structureShapeId, StructureShape::class.java)
        val members : Map<String, MemberShape> = structure.constrainedMembers()
        return members.map { (_, shape) ->
            //shape.asMemberShape().map { it.makeNonConstrained(model, structureShapeId) }
            shape.asMemberShape().map { it.makeNonConstrained(model) }
        }
    }

    // Todo: change the following to use the correct name of the struct using maybe
    // symbolProvider.toSymbol(memberShape).name
    private fun extractedStructureName(memberShape: ShapeId) =
        "${memberShape.namespace}#Refactored${memberShape.name}${memberShape.member.orElse(null)}"

    // Returns the transformation that would be required to turn the given member shape
    // into a non-constrained member shape.
    private fun MemberShape.makeNonConstrained(model : Model) : MemberTransformation? {
        var constrainedTraits = mutableListOf<Trait>()
        var traitsToKeep = mutableListOf<Trait>()

//        val (traitsToKeep, constrainedTraits) = this.allTraits
//            .toList()
//            .partition { constraintTraitIds.any() { ct -> it == ct } }
//            .map { }

        for (t in this.allTraits) {
            // Todo: check why doesn't contains work? and partition also isn't working
            if (constraintTraitIds.any { it.get() == t.key }) {
                constrainedTraits.add(t.value)
            }
            else {
                traitsToKeep.add(t.value)
            }
        }

        if (constrainedTraits.isEmpty())
            return null

        // Create a new standalone shape of the same target as the target of the member e.g.
        // someX : Centigrade, where integer : Centigrade, then the new shape should be
        // integer: newShape
        val shape = model.expectShape(this.target)

        // The shape has to be a buildable type otherwise it cannot be refactored out
        if (shape is ToSmithyBuilder<*>) {
            when (val builder = shape.toBuilder()) {
                 is AbstractShapeBuilder<*,*> -> {
                     val standaloneShape = builder.id(extractedStructureName(this.id))
                         .traits(constrainedTraits)
                         .build()

                     return MemberTransformation(standaloneShape, this, traitsToKeep)
                 }
                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
            }
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions

//
//
//        val standaloneShape = when (shape) {
//                is StringShape -> shape.toBuilder()
//                is FloatShape -> shape.toBuilder()
//                is IntegerShape -> shape.toBuilder()
//                is ShortShape -> shape.toBuilder()
//                is LongShape -> shape.toBuilder()
//                is ByteShape -> shape.toBuilder()
//                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
//        }.id(extractedStructureName(this.id, this.memberName))
//            .traits(constrainedTraits)
//            .build()
//
//        return MemberTransformation(standaloneShape, this, traitsToKeep)
    }

    fun transform(model: Model): Model {
        // Can composite types be constrained? basically do we need to keep track
        // of a subtype that we have already visited and now is being constrained?

        // Todo: maybe go directly to structure shapes instead of from operations
        val transformations: List<Optional<MemberTransformation>> = model.shapes(OperationShape::class.java).toList().flatMap { operation ->
            // it might not have any input structures
            val transformedInputMembers: Optional<List<Optional<MemberTransformation>>> = operation.input.map { shapeId ->
                transformConstrainedMembers(model, shapeId)
            }

            val transformedOutputMembers : Optional<List<Optional<MemberTransformation>>> = operation.output.map { shapeId ->
                transformConstrainedMembers(model, shapeId)
            }

            // combine both the input + output constrained members into a single list and return that
            (transformedInputMembers.orElse(listOf()) + transformedOutputMembers.orElse(listOf())).toList()
        }

        var newShapes = mutableListOf<Shape>()
        for (t: Optional<MemberTransformation> in transformations) {
            if (t.isPresent) {
                val transformation = t.get()
                newShapes.add(transformation.newShape)
            }
        }

        val modelWithExtraShapes = model.toBuilder().addShapes(newShapes).build()

        // Create new member shapes
        var membersToChange = mutableListOf<MemberShape>()
        for (t: Optional<MemberTransformation> in transformations) {
            if (t.isPresent) {
                val transformation = t.get()
                val member = transformation.memberToChange
                val traitsToKeep = transformation.traitsToKeep

                val changedMember = member.toBuilder()
                    .target(transformation.newShape.id)
                    .traits(traitsToKeep)
                    .build()
                membersToChange.add(changedMember)
            }
        }

        // Change all members that needs to be changed
        return ModelTransformer.create()
            .replaceShapes(modelWithExtraShapes, membersToChange)
    }

    interface RefactoringEngine {
        fun refactor(model : Model, member: ShapeId) : MemberTransformation

        companion object {
            // Returns a RefactoringEngine that works with the given combination
            // of traits applied on a member
            fun createForTraits(traits : List<Trait>) =
                RefactorAsStandaloneType()
        }
    }

    /**
     * Extracts the target type into a separate type in the model and replaces
     * the shape's target with that
     */
    class RefactorAsStandaloneType : RefactoringEngine {
        override fun refactor(model: Model, member: ShapeId): MemberTransformation {
            TODO("Not yet implemented")
        }
    }
}
