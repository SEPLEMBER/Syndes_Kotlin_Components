package es.zelliot.epubeditor

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EleganceThroneActivity : AppCompatActivity() {

    private lateinit var passwordField: EditText
    private lateinit var textField: EditText
    private lateinit var codingButton: Button
    private lateinit var uncodingButton: Button
    private lateinit var clearButton: Button
    private lateinit var exitButton: Button

private val passwordHint = "Contraseña"
private val textHint = "Texto"
private val codingText = "Codificar"
private val uncodingText = "Decodificar"
private val clearText = "Limpiar"
private val exitText = "Salir"
private val emptyPasswordMessage = "La contraseña está vacía."
private val emptyTextMessage = "El texto está vacío."
private val codingFailedMessage = "La codificación falló."
private val uncodingFailedMessage = "La decodificación falló."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_elegance_throne)

        passwordField = findViewById(R.id.passwordField)
        textField = findViewById(R.id.textField)
        codingButton = findViewById(R.id.codingButton)
        uncodingButton = findViewById(R.id.uncodingButton)
        clearButton = findViewById(R.id.clearButton)
        exitButton = findViewById(R.id.exitButton)

        passwordField.hint = passwordHint
        textField.hint = textHint
        codingButton.text = codingText
        uncodingButton.text = uncodingText
        clearButton.text = clearText
        exitButton.text = exitText

        codingButton.setOnClickListener { codingFlow() }
        uncodingButton.setOnClickListener { uncodingFlow() }
        clearButton.setOnClickListener { clearFields() }
        exitButton.setOnClickListener { finishAffinity() }
    }

    private fun codingFlow() {
        val password = passwordField.text?.toString().orEmpty()
        val plaintext = textField.text?.toString().orEmpty()

        if (password.isBlank()) {
            toast(emptyPasswordMessage)
            return
        }

        if (plaintext.isBlank()) {
            toast(emptyTextMessage)
            return
        }

        val passwordChars = password.toCharArray()
        try {
            val aes = Secure.Coding(passwordChars, plaintext)
            val elegant = Secure2.Coding(aes).orEmpty()

            textField.setText(elegant)
            if (elegant.isNotEmpty()) textField.setSelection(elegant.length)
        } catch (e: Exception) {
            toast(e.message ?: codingFailedMessage)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun uncodingFlow() {
        val password = passwordField.text?.toString().orEmpty()
        val input = textField.text?.toString().orEmpty()

        if (password.isBlank()) {
            toast(emptyPasswordMessage)
            return
        }

        if (input.isBlank()) {
            toast(emptyTextMessage)
            return
        }

        val passwordChars = password.toCharArray()
        try {
            val decoded = Secure2.Uncoding(input).orEmpty()
            val plaintext = Secure.Uncoding(passwordChars, decoded).orEmpty()

            textField.setText(plaintext)
            if (plaintext.isNotEmpty()) textField.setSelection(plaintext.length)
        } catch (e: Exception) {
            toast(e.message ?: uncodingFailedMessage)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun clearFields() {
        passwordField.text?.clear()
        textField.text?.clear()
        passwordField.requestFocus()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
