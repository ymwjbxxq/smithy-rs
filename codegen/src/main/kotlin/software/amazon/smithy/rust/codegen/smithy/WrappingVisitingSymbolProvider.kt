package software.amazon.smithy.rust.codegen.smithy

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeVisitor
import software.amazon.smithy.model.shapes.TimestampShape
import software.amazon.smithy.model.traits.EnumDefinition
import software.amazon.smithy.rust.codegen.rustlang.RustType

open class WrappingVisitingSymbolProvider(private val base: RustSymbolProvider, private val model: Model) : ShapeVisitor.Default<Symbol>(), RustSymbolProvider {
    override fun getDefault(shape: Shape): Symbol {
        return base.toSymbol(shape)
    }

    override fun config(): SymbolVisitorConfig {
        return base.config()
    }

    override fun toEnumVariantName(definition: EnumDefinition): MaybeRenamed? {
        return base.toEnumVariantName(definition)
    }

    override fun listShape(shape: ListShape): Symbol {
        val inner = toSymbol(shape.member)
        return symbolBuilder(shape, RustType.Vec(inner.rustType())).addReference(inner).build()
    }

    override fun memberShape(shape: MemberShape): Symbol {
        return toSymbol(model.expectShape(shape.target))
    }

    override fun toSymbol(shape: Shape): Symbol {
        return shape.accept(this)
    }

    private fun symbolBuilder(shape: Shape?, rustType: RustType): Symbol.Builder {
        val builder = Symbol.builder().putProperty(SHAPE_KEY, shape)
        return builder.rustType(rustType)
            .name(rustType.name)
            // Every symbol that actually gets defined somewhere should set a definition file
            // If we ever generate a `thisisabug.rs`, there is a bug in our symbol generation
            .definitionFile("thisisabug.rs")
    }
}

class PythonSymbolProvider(base: RustSymbolProvider, model: Model) : WrappingVisitingSymbolProvider(base, model) {
    override fun timestampShape(shape: TimestampShape): Symbol {
        println(shape)
        return Symbol.builder().name("doesThisWork").rustType(RustType.Bool).build()
    }
}
