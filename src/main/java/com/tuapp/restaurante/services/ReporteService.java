package com.tuapp.restaurante.services;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tuapp.restaurante.models.Insumo;
import com.tuapp.restaurante.models.MovimientoInventario;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ReporteService {

    public void generarPdfInsumos(List<Insumo> listInsumos, HttpServletResponse response) throws IOException {
        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, response.getOutputStream());

        document.open();
        Font fontTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD);
        fontTitulo.setSize(18);
        fontTitulo.setColor(new Color(255, 107, 53)); // Color primario #FF6B35

        Paragraph p = new Paragraph("Reporte de Inventario Actual", fontTitulo);
        p.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(p);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100f);
        table.setSpacingBefore(10);

        writeTableHeaderInsumos(table);
        writeTableDataInsumos(table, listInsumos);

        document.add(table);
        document.close();
    }

    private void writeTableHeaderInsumos(PdfPTable table) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(new Color(15, 17, 26)); // Sidebar bg
        cell.setPadding(5);

        Font font = FontFactory.getFont(FontFactory.HELVETICA);
        font.setColor(Color.WHITE);

        cell.setPhrase(new Phrase("ID", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Nombre", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Stock", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Unidad", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Costo Un.", font));
        table.addCell(cell);
    }

    private void writeTableDataInsumos(PdfPTable table, List<Insumo> listInsumos) {
        for (Insumo insumo : listInsumos) {
            table.addCell(String.valueOf(insumo.getId()));
            table.addCell(insumo.getNombre());
            table.addCell(String.valueOf(insumo.getStockActual()));
            table.addCell(insumo.getUnidadMedida());
            table.addCell("$" + insumo.getCostoUnitario());
        }
    }

    public void generarPdfMovimientos(List<MovimientoInventario> listMovs, HttpServletResponse response) throws IOException {
        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, response.getOutputStream());

        document.open();
        Font fontTitulo = FontFactory.getFont(FontFactory.HELVETICA_BOLD);
        fontTitulo.setSize(18);
        fontTitulo.setColor(new Color(16, 185, 129)); // Verde éxito

        Paragraph p = new Paragraph("Historial de Movimientos de Inventario", fontTitulo);
        p.setAlignment(Paragraph.ALIGN_CENTER);
        document.add(p);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100f);
        table.setSpacingBefore(10);

        writeTableHeaderMovimientos(table);
        writeTableDataMovimientos(table, listMovs);

        document.add(table);
        document.close();
    }

    private void writeTableHeaderMovimientos(PdfPTable table) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(new Color(15, 17, 26));
        cell.setPadding(5);

        Font font = FontFactory.getFont(FontFactory.HELVETICA);
        font.setColor(Color.WHITE);

        cell.setPhrase(new Phrase("Fecha", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Tipo", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Insumo", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Canti.", font));
        table.addCell(cell);
        cell.setPhrase(new Phrase("Responsable", font));
        table.addCell(cell);
    }

    private void writeTableDataMovimientos(PdfPTable table, List<MovimientoInventario> list) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        for (MovimientoInventario m : list) {
            table.addCell(m.getFecha().format(formatter));
            table.addCell(m.getTipoMovimiento());
            table.addCell(m.getInsumo().getNombre());
            table.addCell(String.valueOf(m.getCantidad()));
            table.addCell(m.getUsuario() != null ? m.getUsuario().getUsername() : "Sistema");
        }
    }
}
