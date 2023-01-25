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
import software.amazon.smithy.rust.codegen.server.smithy.traits.RefactoredMembershapeTrait
import software.amazon.smithy.rust.codegen.server.smithy.transformers.RefactorConstrainedMemberType.constrainedMembers
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*
import kotlin.streams.toList

object RefactorConstrainedMemberType {
    // Todo: is there a case when we might need to have a List<Shape> as new types that are defined?
    data class MemberTransformation(val newShape : Shape, val memberToChange: MemberShape, val traitsToKeep : List<Trait>)

    // TODO: there must be some way to get these IDs?
    private val constraintTraitIds = setOf(
        LengthTrait.ID,
        PatternTrait.ID,
        RangeTrait.ID,
        UniqueItemsTrait.ID,
        EnumTrait.ID,
        RequiredTrait.ID,
    )

    /// Returns members which have constraint traits applied to them
    private fun StructureShape.constrainedMembers(): Map<String, MemberShape> =
        this.allMembers.filter { (_, memberField) ->
            memberField.hasConstraintTrait()
        }

    private fun transformConstrainedMembers(model : Model, structureShapeId : ShapeId) : List<MemberTransformation> =
        model.expectShape(structureShapeId, StructureShape::class.java)
            .constrainedMembers()
            .map { (_, shape: MemberShape) ->
                shape.makeNonConstrained(model)!!
            }

    // Todo: change the following to use the correct name of the struct using maybe
    // symbolProvider.toSymbol(memberShape).name
    private fun extractedStructureName(memberShape: ShapeId) =
        "${memberShape.namespace}#Refactored${memberShape.name}${memberShape.member.orElse(null)}"

    // Returns the transformation that would be required to turn the given member shape
    // into a non-constrained member shape.
    private fun MemberShape.makeNonConstrained(model : Model) : MemberTransformation? {
        val (constrainedTraits, otherTraits, ) = this.allTraits.toList()
            .partition {
                constraintTraitIds.contains(it.first)
            }
            .let { partIt ->
                val traitsToRefactor = partIt.first.map { it.second }
                // Add refactoring trait to the list of all traits that need to be kept on the member
                val traitsToKeep = partIt.second.map { it.second } + listOf(RefactoredMembershapeTrait(this.id))
                Pair(traitsToRefactor, traitsToKeep)
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

                     return MemberTransformation(standaloneShape, this, otherTraits)
                 }
                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
            }
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions
    }

    fun transform(model: Model): Model {
        // Can composite types be constrained? basically do we need to keep track
        // of a subtype that we have already visited and now is being constrained?

        // Todo: maybe go directly to structure shapes instead of from operations
        val transformations = model.shapes(OperationShape::class.java).toList().flatMap { operation ->
            // it might not have any input structures
            val transformedInputMembers: Optional<List<MemberTransformation>> = operation.input.map { shapeId ->
                transformConstrainedMembers(model, shapeId)
            }

            val transformedOutputMembers: Optional<List<MemberTransformation>> = operation.output.map { shapeId ->
                transformConstrainedMembers(model, shapeId)
            }

            // combine both the input + output constrained members into a single list and return that
            (transformedInputMembers.orElse(listOf()) + transformedOutputMembers.orElse(listOf())).toList()
        }

        val newShapes = transformations.map { it.newShape }

        val modelWithExtraShapes = model.toBuilder()
            .addShapes(newShapes)
            .build()

        // Change all original constrained member shapes with the new stand alone types
        // we have defined.
        val membersToChange = transformations.map {
            it.memberToChange.toBuilder()
                .target(it.newShape.id)
                .traits(it.traitsToKeep)
                .build()
        }

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
