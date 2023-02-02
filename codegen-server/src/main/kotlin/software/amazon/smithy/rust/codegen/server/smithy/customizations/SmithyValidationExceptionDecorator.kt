package software.amazon.smithy.rust.codegen.server.smithy.customizations

import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerRuntimeType
import software.amazon.smithy.rust.codegen.server.smithy.customize.ServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.generators.ConstraintViolation
import software.amazon.smithy.rust.codegen.server.smithy.generators.ValidationExceptionConversionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.StringTraitInfo
import software.amazon.smithy.rust.codegen.server.smithy.generators.TraitInfo

// TODO Docs
class SmithyValidationExceptionDecorator: ServerCodegenDecorator {
    override val name: String
        get() = "SmithyValidationExceptionDecorator"
    override val order: Byte
        get() = 69

    override fun validationExceptionConversion(codegenContext: ServerCodegenContext): ValidationExceptionConversionGenerator =
        SmithyValidationExceptionConversionGenerator(codegenContext)
}

// TODO Docs
class SmithyValidationExceptionConversionGenerator(private val codegenContext: ServerCodegenContext):
    ValidationExceptionConversionGenerator {

    // Define a companion object so that we can refer to this shape id globally.
    companion object {
        val SHAPE_ID: ShapeId = ShapeId.from("smithy.framework#ValidationException")
    }
    override val shapeId: ShapeId = SHAPE_ID

    override fun renderImplFromConstraintViolationForRequestRejection(): Writable = writable {
        val codegenScope = arrayOf(
            "RequestRejection" to ServerRuntimeType.requestRejection(codegenContext.runtimeConfig),
            "From" to RuntimeType.From,
        )
        rustTemplate(
            """
            impl #{From}<ConstraintViolation> for #{RequestRejection} {
                fn from(constraint_violation: ConstraintViolation) -> Self {
                    let first_validation_exception_field = constraint_violation.as_validation_exception_field("".to_owned());
                    let validation_exception = crate::error::ValidationException {
                        message: format!("1 validation error detected. {}", &first_validation_exception_field.message),
                        field_list: Some(vec![first_validation_exception_field]),
                    };
                    Self::ConstraintViolation(
                        crate::operation_ser::serialize_structure_crate_error_validation_exception(&validation_exception)
                            .expect("validation exceptions should never fail to serialize; please file a bug report under https://github.com/awslabs/smithy-rs/issues")
                    )
                }
            }
            """,
            *codegenScope,
        )
    }

    override fun stringShapeConstraintViolationImplBlock(stringConstraintsInfo: Collection<StringTraitInfo>): Writable = writable {
        val constraintsInfo: List<TraitInfo> =
            stringConstraintsInfo
            .map(StringTraitInfo::toTraitInfo)
        rustTemplate(
            """
            pub(crate) fn as_validation_exception_field(self, path: #{String}) -> crate::model::ValidationExceptionField {
                match self {
                    #{ValidationExceptionFields:W}
                }
            }
            """,
            "String" to RuntimeType.String,
            "ValidationExceptionFields" to constraintsInfo.map { it.asValidationExceptionField }.join("\n"),
        )
    }

    override fun builderConstraintViolationImplBlock(constraintViolations: Collection<ConstraintViolation>) = writable {
        rustBlock("match self") {
            constraintViolations.forEach {
                if (it.hasInner()) {
                    rust("""ConstraintViolation::${it.name()}(inner) => inner.as_validation_exception_field(path + "/${it.forMember.memberName}"),""")
                } else {
                    rust(
                        """
                        ConstraintViolation::${it.name()} => crate::model::ValidationExceptionField {
                            message: format!("Value null at '{}/${it.forMember.memberName}' failed to satisfy constraint: Member must not be null", path),
                            path: path + "/${it.forMember.memberName}",
                        },
                        """,
                    )
                }
            }
        }
    }
}
