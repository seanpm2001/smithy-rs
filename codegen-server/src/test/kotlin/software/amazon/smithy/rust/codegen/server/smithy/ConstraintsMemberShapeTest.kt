package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ModelSerializer
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.transformers.RefactorConstrainedMemberType
import software.amazon.smithy.model.node.Node
import java.io.File

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

            @range(max: 200)
            degreeReal : Centigrade

            @range(min: -10, max: 200)
            degreeFeeling : FeelsLikeCentigrade

            @length(min: 1, max: 10)
            historicData: CentigradeList 
        }
        
        structure Coordinate {
            lat : Double
            long : Double
        }

        list CentigradeList {
            member: Centigrade
        }
        
        integer FeelsLikeCentigrade
        float Centigrade
    """.asSmithyModel()

    @Test
    fun `transform model`() {
        val model = RefactorConstrainedMemberType.transform(baseModel)
        println("Transformed model: ${model.toString()}")
        val serializer: ModelSerializer = ModelSerializer.builder().build()
        val json = Node.prettyPrintJson(serializer.serialize(model))

        File("output.txt").printWriter().use {
            it.println(json)
        }
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
