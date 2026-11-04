package com.humblesolutions.finai.ui.split

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.LocalizationRegistry
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.AiConsentPanel
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.strings
import java.io.File

/**
 * Split expense with its own view model, and the platform's pieces: the
 * camera, photo and document pickers for a receipt, the system contact
 * picker, WhatsApp and the clipboard.
 *
 * [currency] and [locale] are the household's, from capabilities.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitRoute(userId: String, currency: String, locale: String, onClose: () -> Unit) {
    val model: SplitViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(userId, currency, locale) { model.bind(userId, currency, locale, context, logging = BuildConfig.DEBUG) }

    // The one coin loader: reading a receipt says so; a first load of a list or a split does not need words.
    val reading = state.receiptStage == ReceiptStage.READING
    val firstLoad = when (state.step) {
        SplitStep.HISTORY -> state.historyLoading && !state.historyLoaded
        SplitStep.REVIEW -> state.openingSplit && state.saved == null
        SplitStep.PEOPLE -> state.contactsLoading && !state.contactsLoaded
        else -> false
    }
    LoaderSignal(key = "split", active = reading || firstLoad, caption = if (reading) strings(Strings.split_reading) else null)

    // A photo of a receipt is only for reading: it goes once it has been read, or could not be.
    // Never on merely arriving at the step, which is where a photo just taken waits to be read.
    LaunchedEffect(state.receiptStage, state.receiptErrorKey) {
        if (state.receiptStage == ReceiptStage.READ || state.receiptErrorKey != null) clearCaptures(context)
    }

    val picked: (Uri?) -> Unit = { uri -> if (uri != null) model.onReceiptPicked(uri.toString()) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picked)
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picked)
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        pendingCapture = null
        if (taken && uri != null) model.onReceiptPicked(uri)
    }
    // The system's own picker, for one phone number: it grants this one row, so no contacts permission.
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val (name, phone) = pickedContact(context, uri) ?: return@rememberLauncherForActivityResult
        model.openNewPerson(name, phone)
    }

    val close = {
        model.discard()
        clearCaptures(context)
        onClose()
    }
    BackHandler { if (!model.back()) close() }

    SplitScreen(
        state = state,
        actions = SplitActions(
            onBack = { if (!model.back()) close() },
            onClose = close,
            onHistory = model::openHistory,
            onChooseSource = model::chooseSource,
            onChooseMode = model::chooseMode,
            onAddPeople = model::openPeople,
            onContinueFromStart = model::continueFromStart,
            onTitleChange = model::onTitleChange,
            onDateChosen = model::onDateChosen,
            onTotalChange = model::onTotalChange,
            onTaxChange = model::onTaxChange,
            onTipChange = model::onTipChange,
            onItemCountChange = model::onItemCountChange,
            onItemChange = model::onItemChange,
            onAddItem = model::addItem,
            onRemoveItem = model::removeItem,
            onContinueFromBill = model::continueFromBill,
            onTakePhoto = {
                val uri = newCapture(context)
                pendingCapture = uri.toString()
                runCatching { camera.launch(uri) }.onFailure { pendingCapture = null }
            },
            onPickPhoto = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickFile = { runCatching { documents.launch(arrayOf("application/pdf", "image/*")) } },
            onRetryReceipt = model::retryReceipt,
            onRetake = model::retake,
            onUseManual = model::useManual,
            onSearchChange = model::onSearchChange,
            onToggleContact = model::toggleContact,
            onRetryContacts = model::loadContacts,
            onPickContact = {
                try {
                    contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                } catch (e: ActivityNotFoundException) {
                    // No contacts app: the person types the number instead.
                    model.openNewPerson()
                }
            },
            onNewPerson = { model.openNewPerson() },
            onAskDeleteContact = model::askDeleteContact,
            onContinueFromPeople = model::continueFromPeople,
            onToggleAssignee = model::toggleAssignee,
            onEveryone = model::assignEveryone,
            onContinueFromAssign = model::continueFromAssign,
            onSave = model::save,
            onSendWhatsApp = { participant ->
                val link = state.whatsAppLinkFor(participant)
                if (link == null || !openWhatsApp(context, link)) model.noWhatsApp(participant.id)
            },
            onToggleSettled = model::toggleSettled,
            onCopy = { text -> copy(context, text) },
            onRetryOpen = model::retryOpen,
            onRetryHistory = model::loadHistory,
            onHistoryShown = model::onHistoryShown,
            onOpenSplit = model::openSplit,
            onAskDeleteSplit = model::askDelete,
        ),
    )

    state.newPerson?.let { draft ->
        NewPersonSheet(
            draft = draft,
            onName = model::onNewPersonName,
            onPhone = model::onNewPersonPhone,
            onSave = model::saveNewPerson,
            onCancel = model::cancelNewPerson,
        )
    }

    state.confirmingContactDelete?.let { contact ->
        DeleteContactDialog(contact = contact, onConfirm = model::deleteContact, onDismiss = model::cancelDeleteContact)
    }

    state.confirmingDelete?.let {
        DeleteSplitDialog(deleting = state.deleting, onConfirm = model::deleteSplit, onDismiss = model::cancelDelete)
    }

    state.noWhatsAppFor?.let { id ->
        state.participant(id)?.let { participant ->
            NoWhatsAppDialog(
                message = state.messageFor(participant).orEmpty(),
                onCopy = { copy(context, it) },
                onDismiss = model::dismissNoWhatsApp,
            )
        }
    }

    state.consent?.let { ask ->
        ModalBottomSheet(onDismissRequest = model::declineConsent) {
            AiConsentPanel(
                title = strings(Strings.split_consent_title),
                policy = ask.policy,
                ticked = ask.ticked,
                busy = ask.busy,
                errorKey = ask.errorKey,
                onTicked = model::onConsentTicked,
                onAgree = model::agree,
                onNotNow = model::declineConsent,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            )
        }
    }
}

/**
 * Opens WhatsApp — or WhatsApp Business — on [link], the person's message
 * typed in for them to send. False when neither is installed.
 */
private fun openWhatsApp(context: Context, link: String): Boolean {
    val uri = Uri.parse(link)
    WHATSAPP.forEach { app ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(app))
            return true
        } catch (e: ActivityNotFoundException) {
            // Not this one; try the next.
        }
    }
    return false
}

private val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(LocalizationRegistry.get(Strings.split_message_preview), text))
}

/** The name and number of the row the contact picker returned, or null if it could not be read. */
private fun pickedContact(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getString(0).orEmpty()
        val phone = cursor.getString(1).orEmpty()
        name to phone
    }
}.getOrNull()

private const val CAPTURES = "receipt-captures"

/** A new file in the app's cache for the camera to write the receipt photo into. */
private fun newCapture(context: Context): Uri {
    val folder = File(context.cacheDir, CAPTURES).apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    val file = File(folder, "receipt-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.captures", file)
}

private fun clearCaptures(context: Context) {
    File(context.cacheDir, CAPTURES).listFiles()?.forEach { it.delete() }
}
