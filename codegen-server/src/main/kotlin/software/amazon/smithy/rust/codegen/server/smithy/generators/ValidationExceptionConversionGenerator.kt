package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider

// TODO Docs
interface ValidationExceptionConversionGenerator {
    val shapeId: ShapeId

    fun renderImplFromConstraintViolationForRequestRejection(): Writable

    // Only rendered when shape lies in constrained operation closure.
    fun stringShapeConstraintViolationImplBlock(stringConstraintsInfo: Collection<StringTraitInfo>): Writable
    fun enumShapeConstraintViolationImplBlock(enumTrait: EnumTrait): Writable
    fun numberShapeConstraintViolationImplBlock(rangeInfo: Range): Writable
    fun blobShapeConstraintViolationImplBlock(blobConstraintsInfo: Collection<BlobLength>): Writable

    fun mapShapeConstraintViolationImplBlock(
        shape: MapShape,
        keyShape: StringShape,
        valueShape: Shape,
        symbolProvider: RustSymbolProvider,
        model: Model,
    ): Writable
    fun builderConstraintViolationImplBlock(constraintViolations: Collection<ConstraintViolation>): Writable
    fun collectionShapeConstraintViolationImplBlock(
        collectionConstraintsInfo: Collection<CollectionTraitInfo>,
        isMemberConstrained: Boolean
    ): Writable
}
