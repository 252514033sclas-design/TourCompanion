package com.example.tourcompanion

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class BudgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_budget)

        findViewById<Button>(R.id.calcBudgetButton).setOnClickListener { calculate() }
        findViewById<Button>(R.id.backBudgetButton).setOnClickListener { finish() }
    }

    private fun number(id: Int): Double =
        findViewById<EditText>(id).text.toString().trim().toDoubleOrNull() ?: 0.0

    private fun calculate() {
        val days = number(R.id.budgetDays).coerceAtLeast(1.0)
        val people = number(R.id.budgetPeople).coerceAtLeast(1.0)
        val stayPerNight = number(R.id.budgetStay)

        val perPersonPerDay = number(R.id.budgetFood) +
                number(R.id.budgetTransport) +
                number(R.id.budgetActivities) +
                number(R.id.budgetOther)

        val subtotal = stayPerNight * days + perPersonPerDay * days * people
        val buffer = subtotal * BUFFER
        val total = subtotal + buffer

        findViewById<TextView>(R.id.budgetResult).text = getString(
            R.string.budget_result,
            money(subtotal),
            money(buffer),
            money(total),
            money(total / people),
            money(total / days)
        )
    }

    private fun money(value: Double): String =
        "₹" + String.format(Locale.US, "%,.0f", value)

    private companion object {
        const val BUFFER = 0.10
    }
}
