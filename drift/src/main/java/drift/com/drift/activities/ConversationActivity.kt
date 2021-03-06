package drift.com.drift.activities

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import java.util.ArrayList

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import drift.com.drift.R
import drift.com.drift.adapters.ConversationAdapter
import drift.com.drift.fragments.ScheduleMeetingDialogFragment
import drift.com.drift.helpers.Alert
import drift.com.drift.helpers.ColorHelper
import drift.com.drift.helpers.DownloadHelper
import drift.com.drift.helpers.LoggerHelper
import drift.com.drift.helpers.MessageReadHelper
import drift.com.drift.helpers.RecyclerTouchListener
import drift.com.drift.helpers.StatusBarColorizer
import drift.com.drift.helpers.UserPopulationHelper
import drift.com.drift.managers.AttachmentManager
import drift.com.drift.managers.MessageManager
import drift.com.drift.model.Attachment
import drift.com.drift.model.Auth
import drift.com.drift.model.Configuration
import drift.com.drift.model.Embed
import drift.com.drift.model.Message
import drift.com.drift.model.MessageRequest
import drift.com.drift.model.User

internal class ConversationActivity : DriftActivity() {


    private lateinit var textEntryEditText: EditText
    private lateinit var sendButtonImageView: ImageView
    private lateinit var plusButtonImageView: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusTextView: TextView
    private lateinit var driftWelcomeMessage: TextView
    private lateinit var driftWelcomeImageView: ImageView
    private lateinit var welcomeMessageLinearLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var conversationAdapter: ConversationAdapter

    private lateinit var driftBrandTextView: TextView
    private var userForWelcomeMessage: User? = null
    private var welcomeMessage: String? = null

    private val downloadReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            //check if the broadcast message is for our enqueued download
            val referenceId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (DownloadHelper.instance.isDownloadFromApp(referenceId)) {
                Toast.makeText(this@ConversationActivity, "Download Complete", Toast.LENGTH_LONG).show()
            }
        }
    }

    private var conversationId: Long = -1L
    private var endUserId: Long = -1L
    private var conversationType = ConversationType.CONTINUE

    private enum class ConversationType {
        CREATE, CONTINUE
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.drift_sdk_activity_conversation)
        StatusBarColorizer.setActivityColor(this)

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        registerReceiver(downloadReceiver, filter)

        textEntryEditText = findViewById(R.id.drift_sdk_conversation_activity_edit_text)

        sendButtonImageView = findViewById(R.id.drift_sdk_conversation_activity_send_button)
        plusButtonImageView = findViewById(R.id.drift_sdk_conversation_activity_plus_button)
        recyclerView = findViewById(R.id.drift_sdk_conversation_activity_recycler_activity)
        statusTextView = findViewById(R.id.drift_sdk_conversation_activity_status_view)
        progressBar = findViewById(R.id.drift_sdk_conversation_activity_progress_view)
        driftWelcomeMessage = findViewById(R.id.drift_sdk_conversation_activity_welcome_text_view)
        driftWelcomeImageView = findViewById(R.id.drift_sdk_conversation_activity_welcome_image_view)
        driftBrandTextView = findViewById(R.id.drift_sdk_conversation_activity_drift_brand_text_view)
        welcomeMessageLinearLayout = findViewById(R.id.drift_sdk_conversation_activity_welcome_linear_layout)

        val intent = intent

        if (intent.extras != null) {
            conversationId = intent.extras!!.getLong(CONVERSATION_ID, -1)
            conversationType = intent.extras!!.getSerializable(CONVERSATION_TYPE) as ConversationType
        }


        if (conversationId == -1L && conversationType == ConversationType.CONTINUE) {
            Toast.makeText(this, "Invalid Conversation Id", Toast.LENGTH_SHORT).show()
            finish()
        }

        val auth = Auth.instance
        if (auth?.endUser != null) {
            endUserId = auth.endUser?.id ?: -1L
        } else {
            //No Auth
            Toast.makeText(this, "We're sorry, an unknown error occurred", Toast.LENGTH_LONG).show()
            finish()
        }

        sendButtonImageView.setBackgroundColor(ColorHelper.backgroundColor)

        val actionBar = supportActionBar
        actionBar?.title = "Conversation"

        sendButtonImageView.setOnClickListener { didPressSendButton() }

        textEntryEditText.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                didPressSendButton()
                return@OnEditorActionListener true
            }
            false
        })

        textEntryEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (s.isNotEmpty()) {
                    sendButtonImageView.visibility = View.VISIBLE
                } else {
                    sendButtonImageView.visibility = View.GONE
                }
            }
        })

        AttachmentManager.setAttachmentLoadHandle {
            didLoadAttachments(it)
        }

        val layoutManager = LinearLayoutManager(this)
        layoutManager.reverseLayout = true
        recyclerView.layoutManager = layoutManager
        recyclerView.addOnItemTouchListener(RecyclerTouchListener(this, recyclerView) { _, position ->
            val message = conversationAdapter.getItemAt(position)
            if (message.sendStatus == Message.SendStatus.FAILED) {
                resendMessage(message)
            }
        })

        conversationAdapter = ConversationAdapter(this, MessageManager.getMessagesForConversationId(conversationId))
        recyclerView.adapter = conversationAdapter

        if (conversationAdapter.itemCount == 0) {
            progressBar.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.GONE
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        AttachmentManager.removeAttachmentLoadHandle()
        unregisterReceiver(downloadReceiver)

    }

    override fun refreshData() {
        super.refreshData()

        statusTextView.visibility = View.GONE

        updateForConversationType()

        if (conversationAdapter.itemCount == 0 && conversationType == ConversationType.CONTINUE) {
            progressBar.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.GONE
        }

        if (conversationId != -1L) {

            MessageManager.getMessagesForConversation(conversationId) { response ->
                if (response != null) {
                    progressBar.visibility = View.GONE
                    LoggerHelper.logMessage(TAG, response.toString())
                    conversationAdapter.updateData(response)

                    if (!response.isEmpty()) {
                        val message = response[0]
                        MessageReadHelper.markMessageAsReadAlongWithPrevious(message)
                    }

                    val attachmentIds = ArrayList<Int>()

                    for (message in response) {

                        if (message.attachmentIds != null) {
                            attachmentIds.addAll(message.attachmentIds!!)
                        }
                    }

                    AttachmentManager.loadAttachments(attachmentIds)

                } else {
                    LoggerHelper.logMessage(TAG, "Failed to load messages")
                }
            }
        }
    }

    private fun updateForConversationType() {
        when (conversationType) {
            ConversationActivity.ConversationType.CREATE -> {

                val embed = Embed.instance
                if (embed?.configuration != null) {
                    welcomeMessage = if (embed.configuration?.isOrgCurrentlyOpen == true) {
                        embed.configuration?.theme?.getWelcomeMessage() ?: ""
                    } else {
                        embed.configuration?.theme?.getAwayMessage() ?: ""
                    }
                    driftWelcomeMessage.text = welcomeMessage

                    updateWelcomeImage(embed.configuration)

                    if (embed.configuration?.showBranding == true) {
                        driftBrandTextView.visibility = View.VISIBLE
                    } else {
                        driftBrandTextView.visibility = View.GONE
                    }

                }
                welcomeMessageLinearLayout.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
            }
            ConversationActivity.ConversationType.CONTINUE -> welcomeMessageLinearLayout.visibility = View.GONE
        }
    }

    private fun updateWelcomeImage(configuration: Configuration?) {


        if (userForWelcomeMessage == null) {

            userForWelcomeMessage = configuration!!.userForWelcomeMessage

            if (userForWelcomeMessage != null) {
                UserPopulationHelper.populateTextAndImageFromUser(this, userForWelcomeMessage, null, driftWelcomeImageView)
            }
        }
    }

    override fun networkNotAvailable() {
        super.networkNotAvailable()
        statusTextView.visibility = View.VISIBLE
    }

    private fun didPressSendButton() {

        when (conversationType) {
            ConversationActivity.ConversationType.CONTINUE -> sendMessage()
            ConversationActivity.ConversationType.CREATE -> createConversation()
        }
        textEntryEditText.setText("")
    }

    fun didReceiveNewMessage(message: Message) {


        val auth = Auth.instance
        if (auth != null && message.authorId == auth.endUser!!.id && message.contentType == "CHAT" && (message.attributes == null || message.attributes!!.appointmentInfo == null) && !message.fakeMessage) {
            LoggerHelper.logMessage(TAG, "Ignoring own message")
            return
        }


        val originalCount = conversationAdapter.itemCount
        val newMessages = MessageManager.addMessageToConversation(conversationId, message)
        if (newMessages.size == originalCount + 1 && (message.attributes == null || message.attributes!!.appointmentInfo == null)) {
            conversationAdapter.updateDataAddingInOneMessage(newMessages, recyclerView)
        } else {
            conversationAdapter.updateData(newMessages)
        }

        MessageReadHelper.markMessageAsReadAlongWithPrevious(message)
    }

    private fun sendMessage() {

        val messageRequest = MessageRequest(textEntryEditText.text.toString(), endUserId, null, this)
        val message = messageRequest.messageFromRequest(conversationId)
        conversationAdapter.addMessage(recyclerView, message)
        sendMessageRequest(messageRequest, message)
    }


    private fun resendMessage(message: Message) {

        val messageRequest = MessageRequest(message.body ?: "", endUserId, null, this)
        sendMessageRequest(messageRequest, message)

    }

    private fun sendMessageRequest(messageRequest: MessageRequest, message: Message) {
        message.sendStatus = Message.SendStatus.SENDING
        conversationAdapter.updateMessage(message)

        MessageManager.sendMessageForConversationId(conversationId, messageRequest) { response ->
            if (response != null) {
                message.sendStatus = Message.SendStatus.SENT
                MessageManager.removeMessageFromFailedCache(message, conversationId)
                conversationAdapter.updateMessage(message)
            } else {
                message.sendStatus = Message.SendStatus.FAILED
                MessageManager.addMessageFailedToConversation(message, conversationId)
                conversationAdapter.updateData(MessageManager.getMessagesForConversationId(conversationId))
            }
        }
    }

    private fun createConversation() {

        val textToSend = textEntryEditText.text.toString()
        val messageRequest = MessageRequest(textToSend, endUserId, null, this)
        val message = messageRequest.messageFromRequest(conversationId)

        progressBar.visibility = View.VISIBLE

        var welcomeUserId: Long? = null
        if (userForWelcomeMessage != null) {
            welcomeUserId = userForWelcomeMessage!!.id
        }

        MessageManager.createConversation(textToSend, welcomeMessage, welcomeUserId) { response ->
            progressBar.visibility = View.GONE

            if (response != null) {
                conversationId = response.conversationId!!
                conversationType = ConversationType.CONTINUE

                message.sendStatus = Message.SendStatus.SENT
                conversationAdapter.addMessage(recyclerView, message)
                updateForConversationType()
                refreshData()
            } else {
                conversationAdapter.updateData(ArrayList())
                textEntryEditText.setText(textToSend)
                Alert.showAlert(this@ConversationActivity, "Error", "Failed to create conversation", "Retry") { didPressSendButton() }
            }
        }
    }

    private fun didLoadAttachments(attachments: ArrayList<Attachment>) {
        LoggerHelper.logMessage(TAG, "Did load attachments: " + attachments.size)
        conversationAdapter.updateForAttachments()
    }

    fun didPressScheduleMeetingFor(userId: Long) {
        ScheduleMeetingDialogFragment.newInstance(userId, conversationId).show(supportFragmentManager, ScheduleMeetingDialogFragment::class.java.simpleName)
    }

    companion object {

        private val TAG = ConversationActivity::class.java.simpleName
        private const val CONVERSATION_ID = "DRIFT_CONVERSATION_ID_PARAM"
        private const val CONVERSATION_TYPE = "DRIFT_CONVERSATION_TYPE_PARAM"

        fun intentForConversation(context: Context, conversationId: Long): Intent {

            val data = Bundle()
            data.putLong(CONVERSATION_ID, conversationId)
            data.putSerializable(CONVERSATION_TYPE, ConversationType.CONTINUE)

            return Intent(context, ConversationActivity::class.java)
                    .putExtras(data)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun intentForCreateConversation(context: Context): Intent {

            val data = Bundle()
            data.putSerializable(CONVERSATION_TYPE, ConversationType.CREATE)


            return Intent(context, ConversationActivity::class.java)
                    .putExtras(data)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun showCreateConversationFromContext(context: Context) {
            val intent = intentForCreateConversation(context)
            context.startActivity(intent)
        }
    }
}
