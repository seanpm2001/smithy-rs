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
        
        use aws.api#data
        
        service WeatherService {
            operation: [GetWeather] 
        }

        operation GetWeather {
            input : WeatherInput
            output : WeatherOutput
        }

        structure WeatherInput {
            coord : Coordinate,
            @length(max: 200)
            cityName: String
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

            @data("content")
            @length(min: 1, max: 10)
            historicData: CentigradeList 
        }
        
        structure Coordinate {
            lat : Double
            @range(min:-50, max:50)
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

    private val simpleModelWithNoConstraints = """
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
            latitude : Float
            longitude: Float
        }
    """.asSmithyModel()

    @Test
    fun `transform model and check all constraints on member shape have been changed`() {
        val model = RefactorConstrainedMemberType.transform(baseModel)

        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$degree")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$city")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$feelsLikeC")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$feelsLikeF")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherOutput\$historicData")
        checkMemberShapeChanged(model, baseModel, "weather#WeatherInput\$cityName")
        // Nested shapes should have also changed
        checkMemberShapeChanged(model, baseModel, "weather#Coordinate\$long")
        // There should be no change in non constrained member shapes
        checkMemberShapeIsSame(model, baseModel, "weather#WeatherOutput\$degreeF")
        checkMemberShapeIsSame(model, baseModel, "weather#WeatherInput\$coord")
        checkMemberShapeIsSame(model, baseModel, "weather#WeatherOutput\$id")
        checkMemberShapeIsSame(model, baseModel, "weather#WeatherOutput\$description")
        checkMemberShapeIsSame(model, baseModel, "weather#Coordinate\$lat")
        // Ensure data trait remained on the member shape in the transformed model.
        checkShapeHasTrait(model, baseModel, "weather#WeatherOutput\$historicData", "aws.api#data")

        val serializer: ModelSerializer = ModelSerializer.builder().build()
        val json = Node.prettyPrintJson(serializer.serialize(model))
        File("output.txt").printWriter().use {
            it.println(json)
        }
    }

    /**
     * Checks there are no side effects off running the transformation on a model
     * that has no constraint types in it at all
     */
    @Test
    fun `Running transformations on a model without constraints has no side effects`() {
        val model = RefactorConstrainedMemberType.transform(simpleModelWithNoConstraints)
        simpleModelWithNoConstraints.let {
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude")
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude")
        }
    }

    /**
     * Checks there are no side effects off running the transformation on a model
     * that has no constraint types in it at all
     */
    @Test
    fun `Running transformations on a model with no member constraints has no side effects`() {
        val simpleModelWithNoMemberConstraints = """
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
                latitude : Latitude
                longitude: Longitude
            }
            
            @range(min:-90, max:90)
            float Latitude
            @range(min:-180, max:180)
            float Longitude
        """.asSmithyModel()

        val model = RefactorConstrainedMemberType.transform(simpleModelWithNoMemberConstraints)
        simpleModelWithNoMemberConstraints.let {
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude")
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude")
        }
    }

    /**
     * Checks there are no side effects off running the transformation on a model
     * that has one empty operation in it.
     */
    @Test
    fun `Model with an additional input output  works`() {
        val modelWithAnEmptyOperation = """
            namespace weather
    
            use aws.protocols#restJson1
    
            @restJson1
            service WeatherService {
                operation: [GetWeather,Test]
            }
    
            operation GetWeather {
                input : WeatherInput
            }
            
            operation Test {
            }
    
            structure WeatherInput {
                latitude : Latitude
                longitude: Longitude
            }
            
            @range(min:-90, max:90)
            float Latitude
            @range(min:-180, max:180)
            float Longitude
        """.asSmithyModel()

        val model = RefactorConstrainedMemberType.transform(modelWithAnEmptyOperation)
        modelWithAnEmptyOperation.let {
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$latitude")
            checkMemberShapeIsSame(model, it, "weather#WeatherInput\$longitude")
        }
    }

    /**
     * Checks that a model with only an empty operation works
     */
    @Test
    fun `Empty operation model works`() {
        val modelWithOnlyEmptyOperation = """
            namespace weather
    
            use aws.protocols#restJson1
    
            @restJson1
            service WeatherService {
                operation: [Test]
            }
    
            operation Test {
            }
        """.asSmithyModel()

        val modelT = RefactorConstrainedMemberType.transform(modelWithOnlyEmptyOperation)
        check(modelWithOnlyEmptyOperation == modelT)
    }

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

    /**
     *  Checks that the given member shape:
     *  1. Has been changed to a new shape
     *  2. New shape has the same type as the original shape's target e.g. float Centigrade,
     *     float newType
     */
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
        val originalConstrainedTraits =
            beforeRefactorShape.allTraits.values.filter { allConstraintTraits.contains(it.javaClass) }.toSet()
        val newShapeConstrainedTraits =
            memberTargetShape.allTraits.values.filter { allConstraintTraits.contains(it.javaClass) }.toSet()
        check((originalConstrainedTraits - newShapeConstrainedTraits).isEmpty())
    }

    /**
     * Checks that the given shape has not changed in the transformed model and is exactly
     * the same as the original model
     */
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

    private fun checkShapeHasTrait(model : Model, orgModel : Model, member: String, traitName: String){
        val memberId = ShapeId.from(member)
        val memberShape = model.expectShape(memberId).asMemberShape().get()
        val orgMemberShape = orgModel.expectShape(memberId).asMemberShape().get()

        check(memberShape.allTraits.keys.contains(ShapeId.from(traitName)))
            { "Given $member does not have the $traitName applied to it" }
        check(orgMemberShape.allTraits.keys.contains(ShapeId.from(traitName)))
            { "Given $member does not have the $traitName applied to it in the original model" }

        val newMemberTrait = memberShape.allTraits[ShapeId.from(traitName)]
        val oldMemberTrait = orgMemberShape.allTraits[ShapeId.from(traitName)]
        check(newMemberTrait == oldMemberTrait) { "Contents of the two traits do not match in the transformed model"}
    }

    private fun checkShapeTargetMatches(model: Model, member: String, targetShapeId: String) =
        check(
            model.expectShape(ShapeId.from(member)).asMemberShape()
                .get().target.name == ShapeId.from(targetShapeId).name,
        )


    @Test
    fun `test malformed model`() {
        val malformedModel = """
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
        val model = RefactorConstrainedMemberType.transform(malformedModel)
        checkShapeTargetMatches(
            model,
            "test#MalformedRangeOverrideInput\$short",
            "test#RefactoredMalformedRangeOverrideInputshort",
        )
    }
}
