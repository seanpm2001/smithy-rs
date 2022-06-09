package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.aws.reterminus.lang.expr.Expr
import software.amazon.smithy.aws.reterminus.lang.expr.Template
import software.amazon.smithy.aws.reterminus.visit.TemplateVisitor
import software.amazon.smithy.rust.codegen.rustlang.Writable
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.writable
import software.amazon.smithy.rust.codegen.util.dq

class TemplateGenerator(
    private val ownership: EndpointsRulesGenerator.Ownership,
    private val exprGenerator: (Expr) -> Writable,
) : TemplateVisitor<Writable> {
    override fun visitStaticTemplate(s: String): Writable {
        return writable {
            rust(s.dq())
            if (ownership == EndpointsRulesGenerator.Ownership.Owned) {
                rust(".to_string()")
            }
        }
    }

    override fun visitSingleDynamicTemplate(p0: Template.Dynamic): Writable {
        return exprGenerator(p0.expr)
    }

    override fun visitStaticElement(p0: String): Writable {
        return writable {
            rust("out.push_str(${p0.dq()});")
        }
    }

    override fun visitDynamicElement(p0: Template.Dynamic): Writable {
        return writable {
            rust("out.push_str(#W);", exprGenerator(p0.expr))
        }
    }

    override fun startMultipartTemplate(): Writable {
        return writable {
            if (ownership == EndpointsRulesGenerator.Ownership.Borrowed) {
                rust("&")
            }
            rust("{ let mut out = String::new(); ")
        }
    }

    override fun finishMultipartTemplate(): Writable {
        return writable { rust("out }") }
    }
}