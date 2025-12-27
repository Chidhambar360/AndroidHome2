package com.example.keyboardlauncher

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment

class NotesFragment : Fragment() {

    private lateinit var editText: EditText

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_notes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        editText = view.findViewById(R.id.notesEditText)

        val prefs = requireContext()
            .getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)

        // Load saved text
        editText.setText(prefs.getString("notes_text", ""))

        // Auto-save while typing
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {}

            override fun afterTextChanged(s: Editable?) {
                prefs.edit()
                    .putString("notes_text", s.toString())
                    .apply()
            }
        })
    }
}
