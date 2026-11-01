package com.inatbox

class InatExxenProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_BSwGQS-pp65t7fK5csiC9l2g" },
) {
    override var mainUrl = "https://diziboxen.help"
}
