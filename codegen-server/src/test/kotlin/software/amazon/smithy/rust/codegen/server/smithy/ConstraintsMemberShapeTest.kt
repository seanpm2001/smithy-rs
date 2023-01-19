package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.smithy.ErrorsModule
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.OperationErrorGenerator
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.renderWithModelBuilder
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.server.smithy.transformers.ChangeConstrainedMemberType

class ConstraintsMemberShapeTest {
    private val baseModel = """
        namespace testmodel

        operation GetPokemonAge {
            input : GetPokemonAgeInput
            output : GetPokemonAgeOutput
        }

        structure GetPokemonAgeOutput {
            color : String
            
            @range(min: 1)
            age : PositiveInteger
            
            @range(max: 100)
            value: PositiveInteger 
        }
        
        structure GetPokemonAgeInput {
            name: String
            
            @range(min: 0, max: 100)
            id : Integer
        }
        
        integer PositiveInteger
    """.asSmithyModel()

    @Test
    fun `transform model`() {
        val model = ChangeConstrainedMemberType.transform(baseModel)
        println("Transformed model: ${model.toString()}")
    }

    @Test
    fun `check how operation normalizer works`() {
        var model = OperationNormalizer.transform(baseModel)
    }
}
