package hr.brendigo.fiskai

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.math.BigDecimal

/** Export only a non-fiscal draft. No invoice number, payment QR or fiscal proof is fabricated. */
internal object DraftPdf {
    fun write(draft: Draft, output: OutputStream) {
        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0
        val navy = Color.rgb(11,31,59)
        val blue = Color.rgb(0,91,234)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 12f }
        fun finish() {
            page?.let { active ->
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 10f }
                active.canvas.drawText("FiskAI • NACRT • Stranica " + pageNumber, 40f, 812f, footer)
                document.finishPage(active)
            }
            page = null
        }
        fun next() {
            finish()
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(595,842,pageNumber).create())
            val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue; textSize = 25f; typeface = Typeface.DEFAULT_BOLD }
            page!!.canvas.drawText("FiskAI",40f,56f,header)
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = navy; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
            page!!.canvas.drawText("NACRT – NIJE IZDANI RAČUN",40f,82f,note)
            page!!.canvas.drawLine(40f,96f,555f,96f,header)
            y = 114
        }
        fun block(value: String, bold: Boolean = false) {
            paint.typeface = if(bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val layout = StaticLayout.Builder.obtain(value,0,value.length,paint,515)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            // Render complete wrapped lines, even when a block spans several pages.
            for(i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i)
                val bottom = layout.getLineBottom(i)
                val height = bottom - top
                if(y + height > 785) next()
                val canvas: Canvas = page!!.canvas
                canvas.save()
                canvas.clipRect(40f,y.toFloat(),555f,(y + height).toFloat())
                canvas.translate(40f,(y-top).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += height
            }
            y += 7
        }
        try {
            next()
            block(draft.kind + " – radna verzija", true)
            block("Kupac: " + draft.customer)
            if(draft.oib.isNotBlank()) block("OIB: " + draft.oib)
            block("Stavke",true)
            draft.lines.forEachIndexed { index, line ->
                block((index+1).toString() + ". " + line.description,true)
                block(line.quantity.toPlainString() + " × " + euro(line.unitPrice) + " | PDV " + line.vatRate.toPlainString() + "%")
                block("Osnovica: " + euro(line.net) + " | PDV: " + euro(line.vat) + " | Ukupno: " + euro(line.total))
            }
            block("Osnovica: " + euro(draft.lines.fold(BigDecimal.ZERO) { a,l -> a + l.net }))
            block("PDV: " + euro(draft.lines.fold(BigDecimal.ZERO) { a,l -> a + l.vat }))
            block("Ukupno: " + euro(draft.total),true)
            block("Ovaj dokument nije izdan, fiskaliziran ni poslan kao eRačun. Nije zahtjev za plaćanje.")
            finish()
            document.writeTo(output)
        } finally {
            // Finish any page before closing on failure.
            try { finish() } finally { document.close() }
        }
    }
}
