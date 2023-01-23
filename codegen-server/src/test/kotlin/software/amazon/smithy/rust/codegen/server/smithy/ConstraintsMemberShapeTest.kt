package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.transform.ModelTransformer
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
        namespace weather
        
        service WeatherService {
            operation: [GetWeather] 
        }

        operation GetWeather {
            input : WeatherInput
            output : WeatherOutput
        }

        structure WeatherInput {
            coord : Coordinate
        }
        
        structure WeatherOutput {
            id : Integer
            main : String
            description : String
            
            @range(max: 200)
            degree : Centigrade            
        }
        
        
        structure Coordinate {
            lat : Double
            long : Double
        }
        
        integer Centigrade
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

    @Test
    fun `print model`() {
        val transformer = ModelTransformer.create()
        transformer.mapShapes(baseModel) {
            println(it)
            it
        }

        val shape = baseModel.getShape(ShapeId.from("weather#Coordinate"))
        println(shape)
    }
}
