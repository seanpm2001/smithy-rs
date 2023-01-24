package software.amazon.smithy.rust.codegen.server.smithy.traits

import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.AnnotationTrait

/**
 * Trait providing data on the original member shape that was refactored due to constraints.
 *
 * This is used for hiding out the fact that the shape has been factored out. All messages
 * should generate the original shape
 */
class RefactoredMembershapeTrait(val memberId: ShapeId): AnnotationTrait(ShapeReachableFromOperationInputTagTrait.ID, Node.objectNode())  {
    companion object {
        val ID = ShapeId.from("smithy.api.internal#refactoredMembershape")
    }
}
