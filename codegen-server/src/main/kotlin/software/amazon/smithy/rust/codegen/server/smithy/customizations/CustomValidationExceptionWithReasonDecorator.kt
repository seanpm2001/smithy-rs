package software.amazon.smithy.rust.codegen.server.smithy.customizations

import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.util.UNREACHABLE
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.ServerRuntimeType
import software.amazon.smithy.rust.codegen.server.smithy.customize.ServerCodegenDecorator
import software.amazon.smithy.rust.codegen.server.smithy.generators.CollectionTraitInfo
import software.amazon.smithy.rust.codegen.server.smithy.generators.ConstraintViolation
import software.amazon.smithy.rust.codegen.server.smithy.generators.CustomValidationExceptionConversionGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.Length
import software.amazon.smithy.rust.codegen.server.smithy.generators.Pattern
import software.amazon.smithy.rust.codegen.server.smithy.generators.StringTraitInfo
import software.amazon.smithy.rust.codegen.server.smithy.generators.TraitInfo
import software.amazon.smithy.rust.codegen.server.smithy.validationErrorMessage

// TODO Docs
class CustomValidationExceptionWithReasonDecorator: ServerCodegenDecorator {
    override val name: String
        get() = "CustomValidationExceptionWithReasonDecorator"
    override val order: Byte
        get() = -69

    override fun customValidationExceptionConversion(codegenContext: ServerCodegenContext):
        CustomValidationExceptionConversionGenerator? =
        if (codegenContext.settings.codegenConfig.experimentalCustomValidationExceptionWithReasonPleaseDoNotUse != null) {
            CustomValidationExceptionWithReasonConversionGenerator(codegenContext)
        } else {
            null
        }
}

// TODO Docs
class CustomValidationExceptionWithReasonConversionGenerator(private val codegenContext: ServerCodegenContext):
    CustomValidationExceptionConversionGenerator {
    override val shapeId: ShapeId =
        ShapeId.from(codegenContext.settings.codegenConfig.experimentalCustomValidationExceptionWithReasonPleaseDoNotUse)

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
                        reason: crate::model::ValidationExceptionReason::FieldValidationFailed,
                        fields: Some(vec![first_validation_exception_field]),
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

    override fun stringShapeConstraintViolationImplBlock(constraintsInfo: Collection<StringTraitInfo>): Writable = writable {
        val validationExceptionFields =
            constraintsInfo.map {
                writable {
                    when (it) {
                        is Pattern -> {
                            rust(
                                """
                                Self::Pattern(string) => crate::model::ValidationExceptionField {
                                    message: format!("${it.patternTrait.validationErrorMessage()}", &string, &path, r##"${it.patternTrait.pattern}"##),
                                    name: path,
                                    reason: crate::model::ValidationExceptionFieldReason::PatternNotValid,
                                },
                                """,
                            )
                        }
                        is Length -> {
                            rust(
                                """
                                Self::Length(length) => crate::model::ValidationExceptionField {
                                    message: format!("${it.lengthTrait.validationErrorMessage()}", length, &path),
                                    name: path,
                                    reason: crate::model::ValidationExceptionFieldReason::LengthNotValid,
                                },
                                """,
                            )
                        }
                    }
                }
            }.join("\n")

        rustTemplate(
            """
            pub(crate) fn as_validation_exception_field(self, path: #{String}) -> crate::model::ValidationExceptionField {
                match self {
                    #{ValidationExceptionFields:W}
                }
            }
            """,
            "String" to RuntimeType.String,
            "ValidationExceptionFields" to validationExceptionFields,
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
                            name: path + "/${it.forMember.memberName}",
                            reason: crate::model::ValidationExceptionFieldReason::Other,
                        },
                        """,
                    )
                }
            }
        }
    }
}
