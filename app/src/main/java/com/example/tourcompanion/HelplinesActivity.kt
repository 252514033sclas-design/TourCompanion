package com.example.tourcompanion

class HelplinesActivity : ListScreenActivity() {

    override fun screenTitle() = getString(R.string.helplines_title)
    override fun screenSubtitle() = getString(R.string.helplines_subtitle)

    override fun buildContent() {
        for (line in Helplines.all) {
            UiKit.addMenuRow(
                container,
                line.icon,
                "${line.name} - ${line.number}",
                line.note
            ) {
                ContactActions.dial(this, line.number)
            }
        }
    }
}
