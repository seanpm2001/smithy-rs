$version: "2.0"

namespace com.amazonaws.constraints

use aws.protocols#restJson1

enum ValidationExceptionFieldReason {
    LENGTH_NOT_VALID = "LengthNotValid"
    PATTERN_NOT_VALID = "PatternNotValid"
    SYNTAX_NOT_VALID = "SyntaxNotValid"
    VALUE_NOT_VALID = "ValueNotValid"
    OTHER = "Other"
}

/// Stores information about a field passed inside a request that resulted in an exception.
structure ValidationExceptionField {
    /// The field name.
    @required
    Name: String

    @required
    Reason: ValidationExceptionFieldReason

    /// Message describing why the field failed validation.
    @required
    Message: String
}

/// A list of fields.
list ValidationExceptionFieldList {
    member: ValidationExceptionField
}

enum ValidationExceptionReason {
    FIELD_VALIDATION_FAILED = "FieldValidationFailed"
    UNKNOWN_OPERATION = "UnknownOperation"
    CANNOT_PARSE = "CannotParse"
    OTHER = "Other"
}

/// The input fails to satisfy the constraints specified by an AWS service.
@error("client")
@httpError(400)
structure ValidationException {
    /// Description of the error.
    @required
    Message: String

    /// Reason the request failed validation.
    @required
    Reason: ValidationExceptionReason

    /// The field that caused the error, if applicable. If more than one field
    /// caused the error, pick one and elaborate in the message.
    Fields: ValidationExceptionFieldList
}

/// A service to test (experimental support for) custom validation exceptions.
@restJson1
@title("CustomValidationExceptionsExperimental")
service CustomValidationExceptionsExperimental {
    operations: [
        ConstrainedShapesOperation,
    ],
}

@http(uri: "/constrained-shapes-operation", method: "POST")
operation ConstrainedShapesOperation {
    input: ConstrainedShapesOperationInputOutput,
    output: ConstrainedShapesOperationInputOutput,
    errors: [ValidationException]
}

structure ConstrainedShapesOperationInputOutput {
    @required
    lengthString: LengthString,
}

@length(min: 2, max: 69)
string LengthString
