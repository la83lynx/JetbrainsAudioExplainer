package com.audioexplainer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.wm.IdeFocusManager

/**
 * App-level owner of the loopback server; started on first access, stopped on IDE shutdown. The server is shared;
 * each open project has its own [ExplainerWorkspace] on it ([ProjectExplainer]).
 */
@Service(Service.Level.APP)
class ExplainerServerService : Disposable {
    val server: ExplainerServer = ExplainerServer().also {
        // An `/api` request that names no project goes to the window the user is in.
        it.focusedWorkspace = {
            IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.let(ProjectExplainer::ifCreated)
        }
        it.start()
    }

    override fun dispose() = server.stop()
}
