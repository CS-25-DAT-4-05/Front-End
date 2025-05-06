import java.io.FileReader;
import java.io.IOException;

public class FrontEndLauncher {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner("CodeWriter.txt"); // Use Coco/R Scanner constructor
        Parser parser = new Parser(scanner);
        parser.Parse();
    }
} 
