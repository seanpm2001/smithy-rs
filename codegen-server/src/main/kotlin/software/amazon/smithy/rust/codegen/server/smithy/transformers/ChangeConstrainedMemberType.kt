package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.ShapeType
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
import java.util.*
import kotlin.streams.toList

object ChangeConstrainedMemberType {
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

    private val primitiveShapes = mutableMapOf<String, ShapeType>()

    init {
        primitiveShapes.put(ShapeType.BOOLEAN.name, ShapeType.BOOLEAN)
        primitiveShapes.put(ShapeType.INTEGER.name, ShapeType.INTEGER)
    }

    /// Returns members which have constraint traits applied to them
    private fun StructureShape.constrainedMembers() =
        this.allMembers.filter { (_, memberField) ->
            memberField.hasConstraintTrait()
        }

    private fun transformConstrainedMembers(model : Model, structureShapeId : ShapeId) : List<Optional<MemberTransformation>> {
        val structure = model.expectShape(structureShapeId, StructureShape::class.java)
        val members : Map<String, MemberShape> = structure.constrainedMembers()
        return members.map { (_, shape) ->
            shape.asMemberShape().map { it.makeNonConstrained(model, structureShapeId) }
        }
    }

    private fun extractedStructureName(memberShape: ShapeId, fieldName : String) =
        "${memberShape.namespace}#Extracted${memberShape.name}${fieldName}"

    // Finds the primitive type of the member shape. So e.g. if x : PositiveInteger
    // then it will find integer because -> `integer PositiveInteger`
    private fun MemberShape.findPrimitiveShapeType(model : Model) : ShapeType {
        val target = model.expectShape(this.target)
        val shapeType: ShapeType? = primitiveShapes[this.target.name]
        shapeType?.let {
            return it
        }

        // Todo: Do we need to go recursive to find the type?
        return ShapeType.INTEGER
    }

    private fun MemberShape.makeNonConstrained(model : Model, outer: ShapeId) : MemberTransformation {
        var constrainedTraits = mutableListOf<Trait>()
        var traitsToKeep = mutableListOf<Trait>()

        for (t in this.allTraits) {
            // Todo: check why doesn't contains work? and partition also isn't working
            if (constraintTraitIds.any { it.get() == t.key }) {
                constrainedTraits.add(t.value)
            }
            else {
                traitsToKeep.add(t.value)
            }
        }

        //this.target.name

        // Extract the shape into an outer shape
//        val newOutsideShape = StructureShape.builder()
//            .id(extractedStructureName(this.id, this.memberName))
//            .traits(constrainedTraits)
//            .build()

        val newOutsideShape = this.findPrimitiveShapeType(model).createBuilderForType()
            .id(extractedStructureName(this.id, this.memberName))
            .traits(constrainedTraits)
            .build()

        // Create a new member shape that targets the new outerShape and has all
        // non-constrained traits on it. However, we cannot do this right now
        // since the model validation fails because the newOutsideShape has not been
        // added to the model yet
        // val changedMember = this.toBuilder()
        //    .target(newOutsideShape.id)
        //    .traits(otherTraits)
        //    .build()

        return MemberTransformation(newOutsideShape, this, traitsToKeep)
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

            (transformedInputMembers.orElse(listOf()) + transformedOutputMembers.orElse(listOf())).toList()
        }

        // Add all new shapes to the model
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
        val transformer = ModelTransformer.create()
        val changedModel: Model = transformer.replaceShapes(modelWithExtraShapes, membersToChange)

        println(changedModel.listShapes)
        return changedModel
    }

    private fun renameConstrainedFieldTarget(field: MemberShape) =
        "${field.id.name}${field.memberName}CM"

    private fun empty(id: ShapeId) = StructureShape.builder().id(id)

    interface RefactoringEngine {
        fun refactor(model : Model, member: ShapeId) : MemberTransformation

        companion object {
            // Returns a RefactoringEngine that works with the given combination
            // of traits applied on a member
            fun createForTraits(traits : List<Trait>) =
                ExtractAsStandaloneFile()
        }
    }

    /**
     * Extracts the target type into a separate type in the model and replaces
     * the shape's target with that
     */
    class ExtractAsStandaloneFile : RefactoringEngine {
        override fun refactor(model: Model, member: ShapeId): MemberTransformation {
            TODO("Not yet implemented")
        }
    }
}
