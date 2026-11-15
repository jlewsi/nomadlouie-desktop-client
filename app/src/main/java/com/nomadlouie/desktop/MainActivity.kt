package com.nomadlouie.desktop

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nomadlouie.desktop.databinding.ActivityMainBinding
import com.nomadlouie.desktop.util.CredStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CredStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CredStore(this)

        binding.hostInput.setText(store.url)
        binding.userInput.setText(store.username)
        binding.passInput.setText(store.password)
        binding.rememberCheck.isChecked = store.remember

        binding.connectButton.setOnClickListener {
            val url = binding.hostInput.text.toString().trim()
            val user = binding.userInput.text.toString().trim()
            val pass = binding.passInput.text.toString()
            if (url.isEmpty()) {
                showError("Server URL is required.")
                return@setOnClickListener
            }
            val normalized = when {
                url.startsWith("https://") || url.startsWith("http://") -> url
                else -> "https://$url"
            }
            store.url = normalized
            if (binding.rememberCheck.isChecked) {
                store.username = user
                store.password = pass
                store.remember = true
            } else {
                store.username = ""
                store.password = ""
                store.remember = false
            }
            val intent = Intent(this, StreamActivity::class.java).apply {
                putExtra(StreamActivity.EXTRA_URL, normalized)
                putExtra(StreamActivity.EXTRA_USER, user)
                putExtra(StreamActivity.EXTRA_PASS, pass)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.statusText.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
