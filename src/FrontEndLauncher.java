public class Main {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(new FileReader("test.txt"));
        Parser parser = new Parser(scanner);
        parser.Parse();
    }
}
