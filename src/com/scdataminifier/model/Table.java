package com.scdataminifier.model;

import com.scdataminifier.ScDataException;

/**
 * A table of up to 16x16 cells. Cells left unset encode with the
 * data-present bit cleared (one byte on the wire). When headerRow is
 * true, row 0 holds the column captions.
 */
public class Table {

    public static final int MAX_DIMENSION = 16;

    private final int rows;
    private final int cols;
    private final boolean headerRow;
    private final TableCell[][] cells;

    public Table(int rows, int cols) {
        this(rows, cols, false);
    }

    public Table(int rows, int cols, boolean headerRow) {
        if (rows < 1 || rows > MAX_DIMENSION || cols < 1 || cols > MAX_DIMENSION) {
            throw new ScDataException("Table dimensions must be 1-" + MAX_DIMENSION + ", got " + rows + "x" + cols);
        }
        this.rows = rows;
        this.cols = cols;
        this.headerRow = headerRow;
        this.cells = new TableCell[rows][cols];
    }

    public Table setCell(int row, int col, TableCell cell) {
        checkBounds(row, col);
        cells[row][col] = cell;
        return this;
    }

    /** @return the cell, or null if empty */
    public TableCell getCell(int row, int col) {
        checkBounds(row, col);
        return cells[row][col];
    }

    private void checkBounds(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) {
            throw new ScDataException("Cell (" + row + "," + col + ") outside table " + rows + "x" + cols);
        }
    }

    public int getRows() { return rows; }

    public int getCols() { return cols; }

    public boolean hasHeaderRow() { return headerRow; }
}
