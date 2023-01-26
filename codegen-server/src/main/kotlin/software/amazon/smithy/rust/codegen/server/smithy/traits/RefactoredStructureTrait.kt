package software.amazon.smithy.rust.codegen.server.smithy.traits

import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.AnnotationTrait

/**
 * Trait applied to a refactored shape indicating the structure that contains member of this new structure type
 */
class RefactoredStructureTrait(): AnnotationTrait(RefactoredStructureTrait.ID, Node.objectNode())  {
    companion object {
        val ID = ShapeId.from("smithy.api.internal#refactoredMember")
    }
}
