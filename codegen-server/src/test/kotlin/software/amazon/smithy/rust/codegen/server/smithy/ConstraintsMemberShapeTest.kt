package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ModelSerializer
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.transform.ModelTransformer
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

            //@uniqueItems
            @length(min: 1, max: 10)
            historicData: CentigradeList 
        }
        
        structure Coordinate {
            lat : Double
            long : Double
        }

        list CentigradeList {
            member: Coordinate
        }
        
        integer FeelsLikeCentigrade
        float Centigrade
    """.asSmithyModel()

    @Test
    fun `transform model`() {
        val model = RefactorConstrainedMemberType.transform(baseModel)

        val degreeShapeId = ShapeId.from("weather#WeatherOutput\$degree")
        val originalDegreeShape = baseModel.expectShape(degreeShapeId).asMemberShape().get()

        val degreeMemberShape = model.expectShape(degreeShapeId).asMemberShape().get()
        val degreeTargetShape = model.expectShape(degreeMemberShape.target)

        // Target shape has to be changed
        check(degreeTargetShape.id.name != "Centigrade")
        // New shape should have all of the constraint traits on it
        check(degreeTargetShape.hasTrait("smithy.api#range"))
        check(!degreeMemberShape.hasTrait("smithy.api#range"))

        val serializer: ModelSerializer = ModelSerializer.builder().build()
        val json = Node.prettyPrintJson(serializer.serialize(model))

        File("output.txt").printWriter().use {
            it.println(json)
        }
    }

    private fun checkShape(model : Model, member : String, targetShapeId: String) {
        val l = model.expectShape(ShapeId.from(member)).asMemberShape().get()
        val name = l.memberName
        val r = ShapeId.from(targetShapeId).name
        check(
            model.expectShape(ShapeId.from(member)).asMemberShape()
                .get().target.name == ShapeId.from(targetShapeId).name
        )
    }

    private val malformedModel = """
    namespace test

    @suppress(["UnstableTrait"])
    @http(uri: "/MalformedRangeOverride", method: "POST")
    operation MalformedRangeOverride {
        input: MalformedRangeOverrideInput,
    }

    structure MalformedRangeOverrideInput {
        @range(min: 4, max: 6)
        short: RangeShort,
        @range(min: 4)
        minShort: MinShort,
        @range(max: 6)
        maxShort: MaxShort,
    
        @range(min: 4, max: 6)
        integer: RangeInteger,
        @range(min: 4)
        minInteger: MinInteger,
        @range(max: 6)
        maxInteger: MaxInteger,
    
        @range(min: 4, max: 6)
        long: RangeLong,
        @range(min: 4)
        minLong: MinLong,
        @range(max: 6)
        maxLong: MaxLong,
    }
    
    @range(min: 2, max: 8)
    short RangeShort
    
    @range(min: 2)
    short MinShort
    
    @range(max: 8)
    short MaxShort
    
    @range(min: 2, max: 8)
    integer RangeInteger
    
    @range(min: 2)
    integer MinInteger
    
    @range(max: 8)
    integer MaxInteger
    
    @range(min: 2, max: 8)
    long RangeLong
    
    @range(min: 2)
    long MinLong
    
    @range(max: 8)
    long MaxLong
    """.asSmithyModel()

    @Test
    fun `test malformed model`() {
        val model = RefactorConstrainedMemberType.transform(malformedModel)
        println(model)
        val serializer: ModelSerializer = ModelSerializer.builder().build()
        val json = Node.prettyPrintJson(serializer.serialize(model))

        File("output.txt").printWriter().use {
            it.println(json)
        }

        val shortType = model.expectShape(ShapeId.from("test#MalformedRangeOverrideInput\$short")).asMemberShape().get()
        println(shortType.target)

        val shortTypeOrg = malformedModel.expectShape(ShapeId.from("test#MalformedRangeOverrideInput\$short")).asMemberShape().get()
        println(shortTypeOrg.target)

        checkShape(model, "test#MalformedRangeOverrideInput\$short", "test#RefactoredMalformedRangeOverrideInputshort")
    }
}
