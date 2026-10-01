package com.humblesolutions.finai.ui.statementimport

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.finai.BuildConfig
import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.ui.components.LoaderSignal
import com.humblesolutions.finai.ui.strings
import com.humblesolutions.finai.usecase.ImportStep
import com.humblesolutions.finai.usecase.StatementImportFlow
import java.io.File

/**
 * The statement import with its own view model, and the platform's pickers:
 * the document picker (PDFs and images), the photo picker, and the camera.
 */
@Composable
internal fun StatementImportRoute(
    userId: String,
    onClose: () -> Unit,
    onTypeInstead: () -> Unit,
    onReview: () -> Unit,
) {
    val model: StatementImportViewModel = viewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(userId) { model.bind(userId, context, logging = BuildConfig.DEBUG) }

    // The one coin loader, saying what the wait is for (#31).
    val caption = when (state.step) {
        ImportStep.READING -> StatementImportFlow.readingProgress(state.page, state.pages)
        ImportStep.SENDING -> strings(Strings.import_sending)
        ImportStep.SAVING -> strings(Strings.import_saving)
        else -> null
    }
    LoaderSignal(key = "statement_import", active = state.working || state.accountsLoading, caption = caption)

    val picked: (Uri?) -> Unit = { uri ->
        if (uri != null) {
            keepAccess(context, uri)
            model.onFilePicked(uri.toString())
        }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), picked)
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), picked)
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = pendingCapture
        pendingCapture = null
        if (taken && uri != null) model.onFilePicked(uri)
    }

    // A photo of a statement is the statement: it does not outlive the import.
    // Deleted when the import is done or abandoned — never on the screen
    // merely leaving composition, which a rotation does mid-read.
    LaunchedEffect(state.step) { if (state.step == ImportStep.DONE) clearCaptures(context) }

    val close = {
        model.discard()
        clearCaptures(context)
        onClose()
    }
    BackHandler {
        when (state.step) {
            ImportStep.CHOOSE_FILE -> model.backToAccount()

            // Nothing to go back to mid-read; the loader covers the screen.
            ImportStep.READING, ImportStep.SENDING, ImportStep.SAVING -> Unit

            else -> close()
        }
    }

    StatementImportScreen(
        state = state,
        actions = StatementImportActions(
            onClose = close,
            onRetryAccounts = model::loadAccounts,
            onChooseAccount = model::chooseAccount,
            onContinueFromAccount = model::continueFromAccount,
            onBackToAccount = model::backToAccount,
            onOpenNewAccount = model::openNewAccount,
            onNewAccountName = model::onNewAccountName,
            onNewAccountKind = model::onNewAccountKind,
            onCreateAccount = model::createAccount,
            onCancelNewAccount = model::cancelNewAccount,
            onPickFile = { documents.launch(arrayOf("application/pdf", "image/*")) },
            onPickPhoto = {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = {
                val uri = newCapture(context)
                pendingCapture = uri.toString()
                camera.launch(uri)
            },
            onSubmitPassword = model::submitPassword,
            onConsentTicked = model::onConsentTicked,
            onAgree = model::agree,
            onDeclineConsent = model::declineConsent,
            onRetry = model::retry,
            onChooseAnother = model::chooseAnotherFile,
            onTypeInstead = {
                model.discard()
                clearCaptures(context)
                onTypeInstead()
            },
            onReview = {
                model.discard()
                clearCaptures(context)
                onReview()
            },
            onDiagnosticsTicked = model::onDiagnosticsTicked,
            onSendDiagnostics = model::sendDiagnostics,
        ),
    )
}

/**
 * Keeps read access to a picked file across the app being killed, so a restore
 * can read it again (#31). Not every provider allows it; where one does not,
 * the grant lasts as long as the system gives it, and a restore that cannot
 * read the file says so like any other unreadable file.
 */
private fun keepAccess(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val CAPTURES = "statement-captures"

/** A new file in the app's cache for the camera to write the photo into. */
private fun newCapture(context: Context): Uri {
    val folder = File(context.cacheDir, CAPTURES).apply { mkdirs() }
    // One photo at a time: an earlier one has been imported or abandoned.
    folder.listFiles()?.forEach { it.delete() }
    val file = File(folder, "statement-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.captures", file)
}

private fun clearCaptures(context: Context) {
    File(context.cacheDir, CAPTURES).listFiles()?.forEach { it.delete() }
}
