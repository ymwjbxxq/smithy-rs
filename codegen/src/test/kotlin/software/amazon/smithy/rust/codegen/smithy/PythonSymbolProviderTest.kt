package software.amazon.smithy.rust.codegen.smithy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.testutil.asSmithyModel

internal class PythonSymbolProviderTest {
    @Test
    fun `symbol provider handles nested context`() {
        val model = """
            namespace test
            list SomeList {
                member: Timestamp
            }
        """.asSmithyModel()
        val provider = PythonSymbolProvider(SymbolVisitor(model, null), model)
        println(provider.toSymbol(model.expectShape(ShapeId.from("test#SomeList"))).rustType())
        println(provider.toSymbol(model.expectShape(ShapeId.from("test#SomeList\$member"))))
    }
}
