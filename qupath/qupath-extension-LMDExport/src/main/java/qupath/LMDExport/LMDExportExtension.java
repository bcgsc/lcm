package qupath.LMDExport;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.icons.PathIconFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;


/**
 * Created by cschlosser on 01/06/2017.
 */
public class LMDExportExtension implements QuPathExtension{

    final private static Logger logger = LoggerFactory.getLogger(LMDExportExtension.class);


    public static void addQuPathCommands(final QuPathGUI qupath) {


        DrawContourMap drawContourMap = new DrawContourMap(qupath);
        ExportLMDMap exportLMDMap = new ExportLMDMap(qupath);
        ExportLMDAblate exportLMDAblate = new ExportLMDAblate(qupath);
        CalculateCellFraction calcCellFrac = new CalculateCellFraction(qupath);



        // Add buttons to toolbar
        qupath.addToolbarSeparator();

        try {
            ImageView imageView = new ImageView(getLMDExportIcon(QuPathGUI.iconSize, QuPathGUI.iconSize));
            Button btnLMDExport = new Button();
            btnLMDExport.setGraphic(imageView);
            btnLMDExport.setTooltip(new Tooltip("LMD Contour Export"));
            ContextMenu popup = new ContextMenu();
            popup.getItems().addAll(
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(drawContourMap, "Draw Contour", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(exportLMDMap, "Export Objects to LMD Map", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(exportLMDAblate, "Export Objects to LMD Ablation Map", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(calcCellFrac, "Calculate Cell Fraction", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null))
            );
            btnLMDExport.setOnMouseClicked(e -> {
                popup.show(btnLMDExport, e.getScreenX(), e.getScreenY());
            });


            qupath.addToolbarButton(btnLMDExport);
        } catch (Exception e) {
            logger.error("Error adding toolbar buttons", e);
            qupath.addToolbarCommand("Draw Contour", drawContourMap, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS));
            qupath.addToolbarCommand("Export Objects to LMD Map", exportLMDMap, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS));
            qupath.addToolbarCommand("Export Objects to LMD Ablation Map", exportLMDAblate, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS));
            qupath.addToolbarCommand("Calculate Cell Fraction", calcCellFrac, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS));
        }



        Menu menuExtension = qupath.getMenu("Extensions>LMD Export", true);
        QuPathGUI.addMenuItems(menuExtension,
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(drawContourMap, "Draw Contour", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(exportLMDMap, "Export Objects to LMD Map", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(exportLMDAblate, "Export Objects to LMD Ablation Map", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null)),
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(calcCellFrac, "Calculate Cell Fraction", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.ANNOTATIONS), null))
        );

    }

    /**
     * Try to read the LMDExport icon from its jar.
     *
     * @param width
     * @param height
     * @return
     */
    public static Image getLMDExportIcon(final int width, final int height) {
        try {
            URL in = LMDExportExtension.class.getResource("/LMD-icon.gif");
            return new Image(in.toString(), width, height, true, true);
//            BufferedImage buffIcon = ImageIO.read(in);
//            ImageView icon = new ImageView( SwingFXUtils.toFXImage(buffIcon, null) );
//            icon.setPreserveRatio(true);
//            icon.setFitHeight(height);
//            icon.setFitWidth(width);
//            return icon.snapshot(null,null);
        } catch (Exception e) {
            logger.error("Unable to load LMD icon!", e);
        }
        return null;
    }


    @Override
    public void installExtension(QuPathGUI qupath) {
        addQuPathCommands(qupath);
    }

    @Override
    public String getName() {
        return "LMD Interface Extension";
    }

    @Override
    public String getDescription() {
        return "Adds functionality for creating contours around detected objects and for exporting those contours as maps to LMD Systems (Leica).";
    }


}
