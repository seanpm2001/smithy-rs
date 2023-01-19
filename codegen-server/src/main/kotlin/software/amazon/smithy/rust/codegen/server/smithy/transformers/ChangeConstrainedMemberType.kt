package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
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
import java.lang.reflect.Member
import java.util.*
import java.util.stream.Stream
import kotlin.streams.toList

object ChangeConstrainedMemberType {
    data class StructureTransformation(val transformedMemberShapes : Map<ShapeId, MemberShape>, val newShapes : List<Shape>)
    data class MemberTransformation(val newShape : StructureShape, val memberToChange: MemberShape, val traitsToKeep : List<Trait>)

    // TODO: there must be some way to get this
    val constraintTraitIds = setOf(
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
            shape.asMemberShape().map { it.makeNonConstrained(structureShapeId) }
        }
    }

    private fun extractedStructureName(outer: ShapeId, memberShape: ShapeId) =
        "${memberShape.namespace}#Extracted${memberShape.name}${memberShape.id}"

    private fun MemberShape.makeNonConstrained(outer: ShapeId) : MemberTransformation {
        val prefix = "${outer.namespace}Extracted"

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

        // Extract the shape into an outer shape
        val newOutsideShape = StructureShape.builder()
            .id(extractedStructureName(outer, this.id))
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
        var newShapes = mutableListOf<StructureShape>()
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

    /**
     * All new types that need to be defined for the given operation
     */
//    fun constrainedMembers(model: Model, operation : OperationShape) : List<StructureShape> {
//        var newShapes = listOf<StructureShape>();
//        operation.input.map { shapeId ->
//            val inputShape: StructureShape = model.expectShape(shapeId, StructureShape::class.java)
//            val fieldsWithConstraints = inputShape.constrainedMembers()
//
//            println(fieldsWithConstraints)
//            //fieldsWithConstraints.forEach(
//                // Define new target names for all of these fields
//            //)
//
//            // 1. change constrained members with a new data type and add that
//            // type to the model
//
//            // 2. have to keep them in the same position in case tomorrow we
//            // use #repr(C)
//            if (!fieldsWithConstraints.isEmpty()) {
//                fieldsWithConstraints.map { (fieldName, fieldShape) ->
//                    fieldShape.toBuilder().id(renameConstrainedFieldTarget("fahad"))
//                    //fieldShape.toBuilder().id("fahad").build()
//                    // Remove the constraint from the field but leave those
//                    // that are non-constraint traits
//                }
//            }
//
//            // 2. keep non constrained traits on the new field
//        }
//
//        return newShapes
//    }

    private fun checkTransformer(model: Model){
        val transformer = ModelTransformer.create()
        transformer.mapShapes(model) {
            println(it)
            it
//            val transformed: Optional<Shape> = it.asOperationShape().map { operation ->
//                model.expectShape(operation.syntheticInputId())
//                operation.toBuilder()
//                    .input(operation.syntheticInputId())
//                    .output(operation.syntheticOutputId())
//                    .build()
//            }
//
//            transformed.orElse(it)
        }

        val changeTo = MemberShape.builder().id("testmodel#GetPokemonAgeOutput\$age").target("fahad").build()
        val model = transformer.replaceShapes(model, listOf(changeTo))
    }

    private fun empty(id: ShapeId) = StructureShape.builder().id(id)
}
