package io.homeassistant.companion.android.onboarding.authentication

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.composethemeadapter.MdcTheme
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.data.HomeAssistantApis
import io.homeassistant.companion.android.common.data.authentication.impl.AuthenticationService
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.onboarding.OnboardingViewModel
import io.homeassistant.companion.android.onboarding.integration.MobileAppIntegrationFragment
import io.homeassistant.companion.android.themes.ThemesManager
import io.homeassistant.companion.android.util.TLSWebViewClient
import io.homeassistant.companion.android.util.isStarted
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import io.homeassistant.companion.android.common.R as commonR

@AndroidEntryPoint
class AuthenticationFragment : Fragment() {

    companion object {
        private const val TAG = "AuthenticationFragment"
        private const val AUTH_CALLBACK = "homeassistant://auth-callback"
    }

    private val viewModel by activityViewModels<OnboardingViewModel>()

    @Inject
    lateinit var themesManager: ThemesManager

    @Inject
    lateinit var keyChainRepository: KeyChainRepository

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                MdcTheme {
                    AndroidView({
                        WebView(requireContext()).apply {
                            themesManager.setThemeForWebView(requireContext(), settings)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString + " ${HomeAssistantApis.USER_AGENT_STRING}"
                            webViewClient = object : TLSWebViewClient(keyChainRepository) {
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String): Boolean {
                                    return onRedirect(url)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    showError(commonR.string.webview_error, null, error)
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (isTLSClientAuthNeeded && !isCertificateChainValid) {
                                        showError(commonR.string.tls_cert_expired_message, null, null)
                                    } else if (isTLSClientAuthNeeded && errorResponse?.statusCode == 400) {
                                        showError(commonR.string.tls_cert_not_found_message, null, null)
                                    } else {
                                        showError(commonR.string.error_ssl, null, null)
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    super.onReceivedSslError(view, handler, error)
                                    showError(commonR.string.error_ssl, error, null)
                                }
                            }
                            loadUrl(buildAuthUrl(viewModel.manualUrl.value))
                        }
                    })
                }
            }
        }
    }

    private fun buildAuthUrl(base: String): String {
        return try {
            base.toHttpUrl()
                .newBuilder()
                .addPathSegments("auth/authorize")
                .addEncodedQueryParameter("response_type", "code")
                .addEncodedQueryParameter("client_id", AuthenticationService.CLIENT_ID)
                .addEncodedQueryParameter("redirect_uri", AUTH_CALLBACK)
                .build()
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to build authentication URL", e)
            Toast.makeText(context, commonR.string.error_connection_failed, Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            ""
        }
    }

    private fun onRedirect(url: String): Boolean {
        val code = Uri.parse(url).getQueryParameter("code")
        return if (url.startsWith(AUTH_CALLBACK) && !code.isNullOrBlank()) {
            viewModel.registerAuthCode(code)
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.content, MobileAppIntegrationFragment::class.java, null)
                .addToBackStack(null)
                .commit()
            true
        } else {
            false
        }
    }

    private fun showError(message: Int, sslError: SslError?, error: WebResourceError?) {
        if (!isStarted) {
            // Fragment is at least paused, can't display alert
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(commonR.string.error_connection_failed)
            .setMessage(
                when (sslError?.primaryError) {
                    SslError.SSL_DATE_INVALID -> commonR.string.webview_error_SSL_DATE_INVALID
                    SslError.SSL_EXPIRED -> commonR.string.webview_error_SSL_EXPIRED
                    SslError.SSL_IDMISMATCH -> commonR.string.webview_error_SSL_IDMISMATCH
                    SslError.SSL_INVALID -> commonR.string.webview_error_SSL_INVALID
                    SslError.SSL_NOTYETVALID -> commonR.string.webview_error_SSL_NOTYETVALID
                    SslError.SSL_UNTRUSTED -> commonR.string.webview_error_SSL_UNTRUSTED
                    else -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            when (error?.errorCode) {
                                WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ->
                                    commonR.string.webview_error_FAILED_SSL_HANDSHAKE
                                WebViewClient.ERROR_AUTHENTICATION -> commonR.string.webview_error_AUTHENTICATION
                                WebViewClient.ERROR_PROXY_AUTHENTICATION -> commonR.string.webview_error_PROXY_AUTHENTICATION
                                WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME -> commonR.string.webview_error_AUTH_SCHEME
                                WebViewClient.ERROR_HOST_LOOKUP -> commonR.string.webview_error_HOST_LOOKUP
                            }
                        }
                        message
                    }
                }
            )
            .setPositiveButton(android.R.string.ok) { _, _ -> }
            .show()
        parentFragmentManager.popBackStack()
    }
}
