package org.jfree.chart.renderer.category.junit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;

import org.jfree.chart.LegendItemCollection;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.util.SortOrder;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;

@RunWith(JQF.class)
public class JQF_AbstractCategoryItemRendererTests {

    @Fuzz
    public void testBugReproduction(boolean hasDataset,
                         @InRange(minInt = 0, maxInt = 7) int rows,
                         @InRange(minInt = 0, maxInt = 6) int cols,
                         @InRange(minDouble = 0, maxDouble = 100) double value,
                         boolean ascending) {
        boolean validIf = true;
        // Bug: null dataset -> NPE; non-null dataset with at least one actual series (needs rows>=1 and cols>=1
        // for DefaultCategoryDataset to hold any row) -> empty legend
        boolean buggyIf = !hasDataset || (rows >= 1 && cols >= 1);

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        CategoryDataset dataset = null;
        if (hasDataset) {
            DefaultCategoryDataset d = new DefaultCategoryDataset();
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    d.addValue(value + i + j, "S" + i, "C" + j);
                }
            }
            dataset = d;
        }
        BarRenderer renderer = new BarRenderer();
        CategoryPlot plot = new CategoryPlot(dataset, new CategoryAxis("c"),
                new NumberAxis("v"), renderer);
        plot.setRowRenderingOrder(ascending ? SortOrder.ASCENDING : SortOrder.DESCENDING);
        final int actualRows = (dataset == null) ? 0 : dataset.getRowCount();

        String exceptionName = "none";
        int count = -1;
        StringBuilder labels = new StringBuilder();
        try {
            LegendItemCollection items = renderer.getLegendItems();
            count = items.getItemCount();
            for (int i = 0; i < count; i++) {
                labels.append(items.get(i).getLabel()).append(";");
            }
        } catch (Exception e) {
            exceptionName = e.getClass().getName();
        }

        final String exc = exceptionName;
        final int cnt = count;
        final String lab = labels.toString();
        Log.logOutIf(true, () -> new Object[]{ exc, cnt, lab, actualRows });
        int expectedCount = actualRows;
        assertEquals("none", exc);
        assertEquals(expectedCount, cnt);
    }

    @Fuzz
    public void test2947660(boolean hasDataset,
                         @InRange(minInt = 0, maxInt = 7) int rows,
                         @InRange(minInt = 0, maxInt = 6) int cols,
                         @InRange(minDouble = 0, maxDouble = 100) double value,
                         boolean ascending) {
        boolean validIf = true;
        boolean buggyIf = !hasDataset || (rows >= 1 && cols >= 1);
        boolean preserveIf = !buggyIf;

        assumeTrue(validIf);
        assumeTrue(preserveIf);

        try {
            CategoryDataset dataset = null;
            if (hasDataset) {
                DefaultCategoryDataset d = new DefaultCategoryDataset();
                for (int i = 0; i < rows; i++) {
                    for (int j = 0; j < cols; j++) {
                        d.addValue(value + i + j, "S" + i, "C" + j);
                    }
                }
                dataset = d;
            }
            BarRenderer renderer = new BarRenderer();
            CategoryPlot plot = new CategoryPlot(dataset, new CategoryAxis("c"),
                    new NumberAxis("v"), renderer);
            plot.setRowRenderingOrder(ascending ? SortOrder.ASCENDING : SortOrder.DESCENDING);
            final int actualRows = (dataset == null) ? 0 : dataset.getRowCount();

            String exceptionName = "none";
            int count = -1;
            StringBuilder labels = new StringBuilder();
            try {
                LegendItemCollection items = renderer.getLegendItems();
                count = items.getItemCount();
                for (int i = 0; i < count; i++) {
                    labels.append(items.get(i).getLabel()).append(";");
                }
            } catch (Exception e) {
                exceptionName = e.getClass().getName();
            }

            final String exc = exceptionName;
            final int cnt = count;
            final String lab = labels.toString();
            Log.logOutIf(preserveIf, () -> new Object[]{ exc, cnt, lab, actualRows });
        } catch (edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException __timeout) {
            throw __timeout;
        } catch (Exception __e) {
            Log.logOutIf(preserveIf, () -> new String[]{ __e.toString() });
        }
    }
}
