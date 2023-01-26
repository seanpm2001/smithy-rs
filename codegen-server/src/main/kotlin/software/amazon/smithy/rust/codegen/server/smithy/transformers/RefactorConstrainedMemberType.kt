package software.amazon.smithy.rust.codegen.server.smithy.transformers

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.AbstractShapeBuilder
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.DirectedWalker
import software.amazon.smithy.rust.codegen.core.util.orNull
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

    /**
     * Transforms all member shapes that have constraints on them into equivalent
     * non-constrained member shapes.
     */
    fun transform(model: Model): Model {
        val walker = DirectedWalker(model)

        val transformations = model.shapes(OperationShape::class.java).toList()
            .flatMap { operation ->
                listOf(operation.input.orNull(), operation.output.orNull())
            }
            .filterNotNull()
            .map { model.expectShape(it) }
            .flatMap {
                walker.walkShapes(it)
            }
            .filterIsInstance(StructureShape::class.java)
            .flatMap {
                it.constrainedMembers()
            }
            .map { it.makeNonConstrained(model)!! }

        return applyTransformations(model, transformations)
    }

    /***
     * Returns a Model that has all the transformations applied on the original
     * model.
     */
    private fun applyTransformations(
        model: Model,
        transformations: List<MemberTransformation>,
    ): Model {
        if (transformations.isEmpty())
            return model

        val modelBuilder = model.toBuilder()
        val memberShapesToChange: MutableList<MemberShape> = mutableListOf()

        for (t in transformations) {
            modelBuilder.addShape(t.newShape)

            val changedMember = t.memberToChange.toBuilder()
                .target(t.newShape.id)
                .traits(t.traitsToKeep)
                .build()
            memberShapesToChange.add(changedMember)
        }

        // Change all original constrained member shapes with the new standalone types,
        // and keep only the non-constraint traits on the member shape.
        return ModelTransformer.create()
            .replaceShapes(modelBuilder.build(), memberShapesToChange)
    }

    /**
     * Returns a list of members that have constraint traits applied to them
     */
    private fun StructureShape.constrainedMembers(): List<MemberShape> =
        this.allMembers.values.filter {
            it.hasConstraintTrait()
        }

    /**
     * Returns the unique (within the model) name of the refactored shape
     */
    private fun nameOfRefactoredShape(model: Model, memberShape: ShapeId): String {
        val structName = memberShape.name
        val memberName = memberShape.member.orElse(null)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        var extractedStructName = "${memberShape.namespace}#Refactored${structName}${memberName}"

        // Ensure the name does not already exist in the model, else make it unique
        // by keep appending a new random number as the suffix.
        for (i in 0..10) {
            if (model.getShape(ShapeId.from(extractedStructName)).isEmpty)
                break;
            extractedStructName = extractedStructName.plus((0..10).random())
        }
        return extractedStructName
    }

    /**
     * Returns the transformation that would be required to turn the given member shape
     * into a non-constrained member shape.
     */
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
                    val standaloneShape = builder.id(nameOfRefactoredShape(model, this.id))
                        .traits(constrainedTraits + RefactoredStructureTrait())
                        .build()

                    // Since the new shape has not been added to the model as yet, the current
                    // memberShape's target cannot be changed to the new shape.
                    // + OriginalShapeIdTrait(this.target)
                    return MemberTransformation(standaloneShape, this, otherTraits)
                }

                else -> throw IllegalStateException("Constraint traits cannot to applied on ${this.id}") // FZ confirm how we are throwing exceptions
            }
        }

        throw IllegalStateException("Constraint traits can only be applied to buildable types. ${this.id} is not buildable") // FZ confirm how we are throwing exceptions
    }
}
