package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.rustlang.Writable

// TODO Docs
interface ValidationExceptionConversionGenerator {
    val shapeId: ShapeId

    fun renderImplFromConstraintViolationForRequestRejection(): Writable

    // Only rendered when shape lies in constrained operation closure.
    fun stringShapeConstraintViolationImplBlock(stringConstraintsInfo: Collection<StringTraitInfo>): Writable
    fun builderConstraintViolationImplBlock(constraintViolations: Collection<ConstraintViolation>): Writable
}
