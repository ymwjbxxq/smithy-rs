package software.amazon.smithy.rust.codegen.endpoints

import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.build.SmithyBuildPlugin

class EndpointCodegenPlugin: SmithyBuildPlugin {
    override fun getName(): String = "endpoints"

    override fun execute(context: PluginContext) {
        val settings = EndpointSettings.fromNode(context.settings)
        TODO("Not yet implemented")
    }
}