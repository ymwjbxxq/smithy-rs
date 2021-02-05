package software.amazon.smithy.protocoltests.traits;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;

public class TraitTest {

    private static Model appliesToModel;

    @Test
    public void simpleRequestTest() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("say-hello.smithy"))
                .discoverModels()
                .assemble()
                .unwrap();
        model.expectShape(ShapeId.from("smithy.example#SomeAwsService"))
                .findTrait("aws.e2e#e2eTests")
                .get();

    }

    @Test
    public void generatedModelTest() {
        Model model = Model.assembler()
                .addImport(getClass().getResource("say-hello.smithy"))
                .addImport(getClass().getResource("generated.smithy.json"))
                .discoverModels()
                .assemble()
                .unwrap();
        model.expectShape(ShapeId.from("smithy.example#SomeAwsService"))
                .findTrait("aws.e2e#e2eTests")
                .get();

    }

}
