package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/** App-level owner of the loopback server; started on first access, stopped on IDE shutdown. */
@Service(Service.Level.APP)
class ExplainerServerService : Disposable {
    val server: ExplainerServer = ExplainerServer().also { it.start() }

    override fun dispose() = server.stop()
}
