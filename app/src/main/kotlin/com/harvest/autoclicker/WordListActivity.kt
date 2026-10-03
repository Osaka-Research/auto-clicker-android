package com.harvest.autoclicker

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class WordListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var newWordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_list)

        container = findViewById(R.id.wordListContainer)
        newWordInput = findViewById(R.id.newWordInput)

        findViewById<Button>(R.id.addWordButton).setOnClickListener {
            val text = newWordInput.text.toString().trim()
            if (text.isNotEmpty()) {
                ClickPrefs.addWord(this, text)
                newWordInput.text.clear()
                refreshList()
            }
        }

        refreshList()
    }

    private fun refreshList() {
        container.removeAllViews()
        ClickPrefs.loadWords(this).forEach { word ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_word, container, false)
            row.findViewById<TextView>(R.id.wordText).text = word.text
            row.findViewById<Button>(R.id.deleteWordButton).setOnClickListener {
                ClickPrefs.deleteWord(this, word.id)
                AutoClickAccessibilityService.instance?.reloadPoints()
                refreshList()
            }
            container.addView(row)
        }
    }
}
