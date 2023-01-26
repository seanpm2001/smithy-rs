package software.amazon.smithy.rust.codegen.server.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ModelSerializer
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.server.smithy.transformers.RefactorConstrainedMemberType
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeCrateLocation
import software.amazon.smithy.rust.codegen.core.testutil.generatePluginContext
import software.amazon.smithy.rust.codegen.server.smithy.customize.CombinedServerCodegenDecorator
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
            @length(max: 100)
            city : String
            description : String
            
            @range(max: 200)
            degree : Centigrade

            degreeF : Fahrenheit

            @range(min: -100, max: 200)
            feelsLikeC : Centigrade
            @range(max: 150)
            feelsLikeF : Fahrenheit

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
        @range(min: -100, max: 200)
        float Fahrenheit
    """.asSmithyModel()

    @Test
    fun `transform model and check all constraints on member shape have been changed`() {
        val model = RefactorConstrainedMemberType.transform(baseModel)

        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$degree")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$city")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$feelsLikeC")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$feelsLikeF")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$historicData")
        checkMemberShapeIsSame(model, baseModel, "weather#WeatherOutput\$degreeF")

        val serializer: ModelSerializer = ModelSerializer.builder().build()
        val json = Node.prettyPrintJson(serializer.serialize(model))

        File("output.txt").printWriter().use {
            it.println(json)
        }
    }

    private val simpleModel = """
        namespace weather

        use aws.protocols#restJson1

        @restJson1
        service WeatherService {
            operation: [GetWeather]
        }

        operation GetWeather {
            input : WeatherInput
        }

        structure WeatherInput {
            latitude : Integer
        }
    """.asSmithyModel()

    @Test
    fun `generate code for a small struct with member shape`() {
        val runtimeConfig =
            RuntimeConfig(runtimeCrateLocation = RuntimeCrateLocation.Path(File("../../rust-runtime").absolutePath))

        val (context, _testDir) = generatePluginContext(baseModel, runtimeConfig = runtimeConfig)
        val codegenDecorator: CombinedServerCodegenDecorator =
            CombinedServerCodegenDecorator.fromClasspath(context)
        ServerCodegenVisitor(context, codegenDecorator)
            .execute()
    }

    private fun checkMemberShapeChanged(model: Model, baseModel: Model, member: String) {
        val memberId = ShapeId.from(member)
        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val memberTargetShape = model.expectShape(memberShape.target)
        val beforeRefactorShape = baseModel.expectShape(memberId).asMemberShape().get()

        // Member shape should not have the @range on it
        check(!memberShape.hasConstraintTrait())
        // Target shape has to be changed to a new shape
        check(memberTargetShape.id.name != beforeRefactorShape.target.name)
        // New shape should have all of the constraint traits that were defined on the original shape
        val originalConstrainedTraits = beforeRefactorShape.allTraits.values.filter {allConstraintTraits.contains(it.javaClass) }.toSet()
        val newShapeConstrainedTraits = memberTargetShape.allTraits.values.filter { allConstraintTraits.contains(it.javaClass) }.toSet()
        check((originalConstrainedTraits - newShapeConstrainedTraits).isEmpty())
    }

    private fun checkMemberShapeIsSame(model: Model, baseModel: Model, member: String) {
        val memberId = ShapeId.from(member)
        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val memberTargetShape = model.expectShape(memberShape.target)
        val beforeRefactorShape = baseModel.expectShape(memberId).asMemberShape().get()

        // Member shape should not have any constraints on it
        check(!memberShape.hasConstraintTrait())
        // Target shape has to be same as the original shape
        check(memberTargetShape.id == beforeRefactorShape.target)
    }


    private fun checkShapeTargetMatches(model: Model, member: String, targetShapeId: String) =
        check(
            model.expectShape(ShapeId.from(member)).asMemberShape()
                .get().target.name == ShapeId.from(targetShapeId).name,
        )

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

        val shortTypeOrg =
            malformedModel.expectShape(ShapeId.from("test#MalformedRangeOverrideInput\$short")).asMemberShape().get()
        println(shortTypeOrg.target)

        checkShapeTargetMatches(model, "test#MalformedRangeOverrideInput\$short", "test#RefactoredMalformedRangeOverrideInputshort")
    }
}
