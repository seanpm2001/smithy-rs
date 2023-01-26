package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.traits.synthetic.OriginalShapeIdTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.server.smithy.allConstraintTraits
import software.amazon.smithy.rust.codegen.server.smithy.hasConstraintTrait
import software.amazon.smithy.rust.codegen.server.smithy.traits.RefactoredStructureTrait
import software.amazon.smithy.utils.ToSmithyBuilder
import java.lang.IllegalStateException
import java.util.*
import kotlin.streams.toList

object RefactorConstrainedMemberType {
    private data class MemberTransformation(
        val newShape: Shape,
        val memberToChange: MemberShape,
        val traitsToKeep: List<Trait>,
    )

    /// Returns members which have constraint traits applied to them
    private fun StructureShape.constrainedMembers(): Map<String, MemberShape> =
        this.allMembers.filter { (_, memberField) ->
            memberField.hasConstraintTrait()
        }

    private fun transformConstrainedMembers(model: Model, structureShapeId: ShapeId): List<MemberTransformation> =
        model.expectShape(structureShapeId, StructureShape::class.java)
            .constrainedMembers()
            .map { (_, shape: MemberShape) ->
                shape.makeNonConstrained(model)!!
            }

    // Todo: change the following to use the correct name of the struct using maybe
    // symbolProvider.toSymbol(memberShape).name
    private fun extractedStructureName(model: Model, memberShape: ShapeId): String {
        val structName = memberShape.name
        val memberName = memberShape.member.orElse(null)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        var extractedStructName = "${memberShape.namespace}#Refactored${structName}${memberName}"

        // Ensure the name does not already exist in the model, else make it unique
        // by appending a random number as the suffix
        for (i in 0..10) {
            if (model.getShape(ShapeId.from(extractedStructName)).isEmpty)
                break;
            extractedStructName = extractedStructName.plus((0..10).random())
        }
        return extractedStructName
    }

    // Returns the transformation that would be required to turn the given member shape
    // into a non-constrained member shape.
    private fun MemberShape.makeNonConstrained(model: Model): MemberTransformation? {
        val (constrainedTraits, otherTraits) = this.allTraits.values
            .partition {
                allConstraintTraits.contains(it.javaClass)
            }

        // No transformation required in case the given MemberShape has no constraints.
        if (constrainedTraits.isEmpty())
            return null

        // Create a new standalone shape of the same target as the target of the member e.g.
        // someX : Centigrade, where integer : Centigrade, then the new shape should be
        // integer: newShape
        val targetShape = model.expectShape(this.target)

        // The shape has to be a buildable type otherwise it cannot be refactored.
        if (targetShape is ToSmithyBuilder<*>) {
            when (val builder = targetShape.toBuilder()) {
                is AbstractShapeBuilder<*, *> -> {
                    // Use the target builder to create a new standalone shape that would
                    // be added to the model later on. Put all the constraint traits that
                    // were on the member shape onto the new shape. Also apply the
                    // RefactoredMemberTrait on the new shape.
                    val standaloneShape = builder.id(extractedStructureName(model, this.id))
                        .traits(constrainedTraits + RefactoredStructureTrait())
                        .build()

                    // Since the new shape has not been added to the model as yet, the current
                    // memberShape's target cannot be changed to the new shape.
                    return MemberTransformation(standaloneShape, this, otherTraits + OriginalShapeIdTrait(this.target))
                }

                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
            }
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions
    }

    fun transform(model: Model): Model {
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

        // Change all original constrained member shapes with the new standalone types,
        // and keep only the non-constraint traits on the member shape.
        val membersToChange = transformations.map {
            it.memberToChange.toBuilder()
                .target(it.newShape.id)
                .traits(it.traitsToKeep)
                .build()
        }

        return ModelTransformer.create()
            .replaceShapes(modelWithExtraShapes, membersToChange)
    }
}
