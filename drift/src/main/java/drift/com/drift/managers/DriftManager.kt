package drift.com.drift.managers


import drift.com.drift.helpers.LoggerHelper
import drift.com.drift.helpers.LogoutHelper
import drift.com.drift.model.Auth
import drift.com.drift.model.Embed
import drift.com.drift.model.IdentifyResponse
import drift.com.drift.model.SocketAuth
import drift.com.drift.wrappers.APICallbackWrapper
import drift.com.drift.wrappers.DriftManagerWrapper

/**
 * Created by eoin on 28/07/2017.
 */

class DriftManager {

    private var registerInformation: RegisterInformation? = null

    var loadingUser: Boolean? = false

    fun getDataFromEmbeds(embedId: String) {
        val embed = Embed.instance
        if (embed != null && embed!!.id != embedId) {
            LogoutHelper.logout()
        }

        DriftManagerWrapper.getEmbed(embedId) { response ->
            if (response != null) {
                response.saveEmbed()
                LoggerHelper.logMessage(TAG, "Get Embed Success")

                if (registerInformation != null) {
                    registerUser(registerInformation!!.userId, registerInformation!!.email)
                }

            }
        }
    }

    fun registerUser(userId: String, email: String) {

        val embed = Embed.instance

        if (embed == null) {
            LoggerHelper.logMessage(TAG, "No Embed, not registering yet")
            registerInformation = RegisterInformation(userId, email)
            return
        }

        if (loadingUser!!) {
            return
        }

        registerInformation = null

        ///Post Identify
        loadingUser = true
        DriftManagerWrapper.postIdentity(embed!!.orgId!!, userId, email) { response ->
            if (response != null) {
                LoggerHelper.logMessage(TAG, "Identify Complete")
                getAuth(embed!!, response, email)
            } else {
                LoggerHelper.logMessage(TAG, "Identify Failed")
                loadingUser = false
            }
        }
    }

    private fun getAuth(embed: Embed, identifyResponse: IdentifyResponse, email: String) {

        DriftManagerWrapper.getAuth(identifyResponse.orgId!!, identifyResponse.userId, email, embed.configuration!!.redirectUri, embed.configuration!!.authClientId) { response ->
            if (response != null) {
                response.saveAuth()
                LoggerHelper.logMessage(TAG, "Auth Complete")
                getSocketAuth(embed.orgId!!, response.accessToken)
            } else {
                LoggerHelper.logMessage(TAG, "Auth Failed")
                loadingUser = false
            }
        }
    }

    private fun getSocketAuth(orgId: Int, accessToken: String) {
        DriftManagerWrapper.getSocketAuth(orgId, accessToken) { response ->
            if (response != null) {
                LoggerHelper.logMessage(TAG, "Socket Auth Complete")
                SocketManager.instance.connect(response)
            } else {
                LoggerHelper.logMessage(TAG, "Socket Auth Failed")
            }
            loadingUser = false
        }
    }

    private inner class RegisterInformation internal constructor(private val userId: String, private val email: String)

    companion object {

        private val TAG = DriftManager::class.java.simpleName

        val instance = DriftManager()
    }
}