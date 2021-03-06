package drift.com.drift.managers

import java.util.ArrayList
import drift.com.drift.model.ConversationExtra
import drift.com.drift.wrappers.ConversationListWrapper

/**
 * Created by eoin on 08/08/2017.
 */

internal object ConversationManager {

    var conversations = ArrayList<ConversationExtra>()
        private set

    private var manuallyAddedUnreadMessages = 0

    var isApiCallComplete = false
        private set

    val unreadCountForUser: Int
        get() {

            var unreadCount = manuallyAddedUnreadMessages

            for (conversationExtra in conversations) {
                if (conversationExtra.unreadMessages != 0) {
                    unreadCount += conversationExtra.unreadMessages
                }
            }

            return unreadCount
        }

    fun manuallyAddUnreadCount() {
        manuallyAddedUnreadMessages += 1
    }

    fun clearCache() {
        conversations = ArrayList()
        isApiCallComplete = false
    }

    fun getConversationsForEndUser(endUserId: Long?, conversationsCallback: (response: ArrayList<ConversationExtra>?) -> Unit) {

        ConversationListWrapper.getConversationsForEndUser(endUserId) { response ->
            isApiCallComplete = true
            if (response != null) {
                manuallyAddedUnreadMessages = 0

                val filteredConversationExtras = ArrayList<ConversationExtra>()

                for (conversationExtra in response) {
                    if (conversationExtra.conversation != null && conversationExtra.conversation!!.type != "EMAIL") {

                        if (DriftManager.showAutomatedMessages) {
                            //Add all conversations
                            filteredConversationExtras.add(conversationExtra)
                        } else {
                            //Only add conversations we have a status for
                            if (conversationExtra.conversation?.conversationStatus != null) {
                                filteredConversationExtras.add(conversationExtra)
                            }
                        }
                    }
                }
                conversations = filteredConversationExtras
            }

            conversationsCallback(conversations)
        }

    }
}
