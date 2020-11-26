package qupath.dl4j;


import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;

import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;


import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.icons.PathIconFactory;



/**
 * Created by cschlosser on 01/06/2017.
 */
public class DL4JExtension implements QuPathExtension{

    final private static Logger logger = LoggerFactory.getLogger(DL4JExtension.class);


    public static void addQuPathCommands(final QuPathGUI qupath) {


        SaveCNNTrainingDataCommand saveCNNTrainingData = new SaveCNNTrainingDataCommand(qupath);
        ClassifyTilesCNNCommand classifyTilesCNN = new ClassifyTilesCNNCommand(qupath);
        TrainCNNCommand trainCNN = new TrainCNNCommand();


        // Add buttons to toolbar
        qupath.addToolbarSeparator();

        try {
            ImageView imageView = new ImageView(getDL4JIcon(QuPathGUI.iconSize, QuPathGUI.iconSize));
            Button btnDL4J = new Button();
            btnDL4J.setGraphic(imageView);
            btnDL4J.setTooltip(new Tooltip("DL4J commands"));
            ContextMenu popup = new ContextMenu();
            popup.getItems().addAll(
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(saveCNNTrainingData, "Save CNN Training Data", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID), null)),
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(trainCNN, "Train CNN", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.TMA_GRID), null)),
                    QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(classifyTilesCNN, "Tile and Classify with CNN", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID), null))


            );
            btnDL4J.setOnMouseClicked(e -> {
                popup.show(btnDL4J, e.getScreenX(), e.getScreenY());
            });


            qupath.addToolbarButton(btnDL4J);
        } catch (Exception e) {
            logger.error("Error adding toolbar buttons", e);
            qupath.addToolbarCommand("Save CNN Training Data", saveCNNTrainingData, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID));
            qupath.addToolbarCommand("Train CNN", trainCNN, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.TMA_GRID));
            qupath.addToolbarCommand("Classify with CNN", classifyTilesCNN, PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID));
        }




        Menu menuExtension = qupath.getMenu("Extensions>DL4J", true);
        QuPathGUI.addMenuItems(menuExtension,
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(saveCNNTrainingData, "Save CNN Training Data", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID), null)),
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(trainCNN, "Train CNN", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID), null)),
                QuPathGUI.createMenuItem(QuPathGUI.createCommandAction(classifyTilesCNN, "Tile and Classify with CNN", PathIconFactory.createNode(QuPathGUI.iconSize, QuPathGUI.iconSize, PathIconFactory.PathIcons.GRID), null))

        );

    }

    /**
     * Try to read the ImageJ icon from its jar.
     *
     * @param width
     * @param height
     * @return
     */
    public static Image getDL4JIcon(final int width, final int height) {
        try {
            URL in = DL4JExtension.class.getResource("/dl4j-icon.gif");
            return new Image(in.toString(), width, height, true, true);
//            InputStream in = DL4JExtension.class.getResourceAsStream("/dl4j-icon.gif");//new URL("src/main/resources/dl4j-icon.bmp");
//            BufferedImage buffIcon = ImageIO.read(in);
//            ImageView icon = new ImageView( SwingFXUtils.toFXImage(buffIcon, null) );
//            icon.setPreserveRatio(false);
//            icon.setFitHeight(height);
//            icon.setFitWidth(width);
//            return icon.snapshot(null,null);
        } catch (Exception e) {
            logger.error("Unable to load DL4J icon!", e);
        }
        return null;
    }


    @Override
    public void installExtension(QuPathGUI qupath) {
        addQuPathCommands(qupath);
    }

    @Override
    public String getName() {
        return "DL4J Tiled Deep Learning Extension";
    }

    @Override
    public String getDescription() {
        return "Adds functionality for CNN based classification of image tiles.";
    }


}
