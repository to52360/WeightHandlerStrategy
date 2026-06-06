package lin.ui.components

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox

class PaginationBar(
    private val onPageChange: (Int) -> Unit
) : HBox(5.0) {

    private val pageLabel = Label("1 / 1")
    private val prevBtn = Button("<")
    private val nextBtn = Button(">")

    var currentPage: Int = 1
        private set

    var pageSize: Int = 20
        private set

    var totalItems: Int = 0
        private set

    init {
        alignment = Pos.CENTER
        children.addAll(prevBtn, pageLabel, nextBtn)

        prevBtn.setOnAction {
            if (currentPage > 1) {
                currentPage--
                updateUI()
                onPageChange(currentPage)
            }
        }

        nextBtn.setOnAction {
            if (currentPage < getTotalPages()) {
                currentPage++
                updateUI()
                onPageChange(currentPage)
            }
        }
    }

    fun update(total: Int, page: Int = currentPage, size: Int = pageSize) {
        totalItems = total
        pageSize = size
        currentPage = page

        val totalPages = getTotalPages()
        if (currentPage > totalPages) {
            currentPage = totalPages
        }
        if (currentPage < 1) {
            currentPage = 1
        }

        updateUI()
    }

    fun reset() {
        currentPage = 1
    }

    private fun getTotalPages(): Int {
        val pages = (totalItems + pageSize - 1) / pageSize
        return if (pages <= 0) 1 else pages
    }

    private fun updateUI() {
        pageLabel.text = "$currentPage / ${getTotalPages()}"
        prevBtn.isDisable = currentPage <= 1
        nextBtn.isDisable = currentPage >= getTotalPages()
    }
}
