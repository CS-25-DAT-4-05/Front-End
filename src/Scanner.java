
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.HashMap;

class Token {
	public int kind;    // token kind
	public int pos;     // token position in bytes in the source text (starting at 0)
	public int charPos; // token position in characters in the source text (starting at 0)
	public int col;     // token column (starting at 1)
	public int line;    // token line (starting at 1)
	public String val;  // token value
	public Token next;  // ML 2005-03-11 Peek tokens are kept in linked list
}

//-----------------------------------------------------------------------------------
// Buffer
//-----------------------------------------------------------------------------------
class Buffer {
	// This Buffer supports the following cases:
	// 1) seekable stream (file)
	//    a) whole stream in buffer
	//    b) part of stream in buffer
	// 2) non seekable stream (network, console)

	public static final int EOF = Character.MAX_VALUE + 1;
	private static final int MIN_BUFFER_LENGTH = 1024; // 1KB
	private static final int MAX_BUFFER_LENGTH = MIN_BUFFER_LENGTH * 64; // 64KB
	private byte[] buf;   // input buffer
	private int bufStart; // position of first byte in buffer relative to input stream
	private int bufLen;   // length of buffer
	private int fileLen;  // length of input stream (may change if stream is no file)
	private int bufPos;      // current position in buffer
	private RandomAccessFile file; // input stream (seekable)
	private InputStream stream; // growing input stream (e.g.: console, network)

	public Buffer(InputStream s) {
		stream = s;
		fileLen = bufLen = bufStart = bufPos = 0;
		buf = new byte[MIN_BUFFER_LENGTH];
	}

	public Buffer(String fileName) {
		try {
			file = new RandomAccessFile(fileName, "r");
			fileLen = (int) file.length();
			bufLen = Math.min(fileLen, MAX_BUFFER_LENGTH);
			buf = new byte[bufLen];
			bufStart = Integer.MAX_VALUE; // nothing in buffer so far
			if (fileLen > 0) setPos(0); // setup buffer to position 0 (start)
			else bufPos = 0; // index 0 is already after the file, thus setPos(0) is invalid
			if (bufLen == fileLen) Close();
		} catch (IOException e) {
			throw new FatalError("Could not open file " + fileName);
		}
	}

	// don't use b after this call anymore
	// called in UTF8Buffer constructor
	protected Buffer(Buffer b) {
		buf = b.buf;
		bufStart = b.bufStart;
		bufLen = b.bufLen;
		fileLen = b.fileLen;
		bufPos = b.bufPos;
		file = b.file;
		stream = b.stream;
		// keep finalize from closing the file
		b.file = null;
	}

	protected void Close() {
		if (file != null) {
			try {
				file.close();
				file = null;
			} catch (IOException e) {
				throw new FatalError(e.getMessage());
			}
		}
	}

	public int Read() {
		if (bufPos < bufLen) {
			return buf[bufPos++] & 0xff;  // mask out sign bits
		} else if (getPos() < fileLen) {
			setPos(getPos());         // shift buffer start to pos
			return buf[bufPos++] & 0xff; // mask out sign bits
		} else if (stream != null && ReadNextStreamChunk() > 0) {
			return buf[bufPos++] & 0xff;  // mask out sign bits
		} else {
			return EOF;
		}
	}

	public int Peek() {
		int curPos = getPos();
		int ch = Read();
		setPos(curPos);
		return ch;
	}

	// beg .. begin, zero-based, inclusive, in byte
	// end .. end, zero-based, exclusive, in byte
	public String GetString(int beg, int end) {
		int len = 0;
		char[] buf = new char[end - beg];
		int oldPos = getPos();
		setPos(beg);
		while (getPos() < end) buf[len++] = (char) Read();
		setPos(oldPos);
		return new String(buf, 0, len);
	}

	public int getPos() {
		return bufPos + bufStart;
	}

	public void setPos(int value) {
		if (value >= fileLen && stream != null) {
			// Wanted position is after buffer and the stream
			// is not seek-able e.g. network or console,
			// thus we have to read the stream manually till
			// the wanted position is in sight.
			while (value >= fileLen && ReadNextStreamChunk() > 0);
		}

		if (value < 0 || value > fileLen) {
			throw new FatalError("buffer out of bounds access, position: " + value);
		}

		if (value >= bufStart && value < bufStart + bufLen) { // already in buffer
			bufPos = value - bufStart;
		} else if (file != null) { // must be swapped in
			try {
				file.seek(value);
				bufLen = file.read(buf);
				bufStart = value; bufPos = 0;
			} catch(IOException e) {
				throw new FatalError(e.getMessage());
			}
		} else {
			// set the position to the end of the file, Pos will return fileLen.
			bufPos = fileLen - bufStart;
		}
	}
	
	// Read the next chunk of bytes from the stream, increases the buffer
	// if needed and updates the fields fileLen and bufLen.
	// Returns the number of bytes read.
	private int ReadNextStreamChunk() {
		int free = buf.length - bufLen;
		if (free == 0) {
			// in the case of a growing input stream
			// we can neither seek in the stream, nor can we
			// foresee the maximum length, thus we must adapt
			// the buffer size on demand.
			byte[] newBuf = new byte[bufLen * 2];
			System.arraycopy(buf, 0, newBuf, 0, bufLen);
			buf = newBuf;
			free = bufLen;
		}
		
		int read;
		try { read = stream.read(buf, bufLen, free); }
		catch (IOException ioex) { throw new FatalError(ioex.getMessage()); }
		
		if (read > 0) {
			fileLen = bufLen = (bufLen + read);
			return read;
		}
		// end of stream reached
		return 0;
	}
}

//-----------------------------------------------------------------------------------
// UTF8Buffer
//-----------------------------------------------------------------------------------
class UTF8Buffer extends Buffer {
	UTF8Buffer(Buffer b) { super(b); }

	public int Read() {
		int ch;
		do {
			ch = super.Read();
			// until we find a utf8 start (0xxxxxxx or 11xxxxxx)
		} while ((ch >= 128) && ((ch & 0xC0) != 0xC0) && (ch != EOF));
		if (ch < 128 || ch == EOF) {
			// nothing to do, first 127 chars are the same in ascii and utf8
			// 0xxxxxxx or end of file character
		} else if ((ch & 0xF0) == 0xF0) {
			// 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
			int c1 = ch & 0x07; ch = super.Read();
			int c2 = ch & 0x3F; ch = super.Read();
			int c3 = ch & 0x3F; ch = super.Read();
			int c4 = ch & 0x3F;
			ch = (((((c1 << 6) | c2) << 6) | c3) << 6) | c4;
		} else if ((ch & 0xE0) == 0xE0) {
			// 1110xxxx 10xxxxxx 10xxxxxx
			int c1 = ch & 0x0F; ch = super.Read();
			int c2 = ch & 0x3F; ch = super.Read();
			int c3 = ch & 0x3F;
			ch = (((c1 << 6) | c2) << 6) | c3;
		} else if ((ch & 0xC0) == 0xC0) {
			// 110xxxxx 10xxxxxx
			int c1 = ch & 0x1F; ch = super.Read();
			int c2 = ch & 0x3F;
			ch = (c1 << 6) | c2;
		}
		return ch;
	}
}

//-----------------------------------------------------------------------------------
// StartStates  -- maps characters to start states of tokens
//-----------------------------------------------------------------------------------
class StartStates {
	private static class Elem {
		public int key, val;
		public Elem next;
		public Elem(int key, int val) { this.key = key; this.val = val; }
	}

	private Elem[] tab = new Elem[128];

	public void set(int key, int val) {
		Elem e = new Elem(key, val);
		int k = key % 128;
		e.next = tab[k]; tab[k] = e;
	}

	public int state(int key) {
		Elem e = tab[key % 128];
		while (e != null && e.key != key) e = e.next;
		return e == null ? 0: e.val;
	}
}

//-----------------------------------------------------------------------------------
// Scanner
//-----------------------------------------------------------------------------------
public class Scanner {
	static final char EOL = '\n';
	static final int  eofSym = 0;
	static final int maxT = 101;
	static final int noSym = 101;


	public Buffer buffer; // scanner buffer

	Token t;           // current token
	int ch;            // current input character
	int pos;           // byte position of current character
	int charPos;       // position by unicode characters starting with 0
	int col;           // column number of current character
	int line;          // line number of current character
	int oldEols;       // EOLs that appeared in a comment;
	static final StartStates start; // maps initial token character to start state
	static final Map literals;      // maps literal strings to literal kinds

	Token tokens;      // list of tokens already peeked (first token is a dummy)
	Token pt;          // current peek token
	
	char[] tval = new char[16]; // token text used in NextToken(), dynamically enlarged
	int tlen;          // length of current token


	static {
		start = new StartStates();
		literals = new HashMap();
		for (int i = 36; i <= 36; ++i) start.set(i, 1);
		for (int i = 65; i <= 90; ++i) start.set(i, 1);
		for (int i = 95; i <= 95; ++i) start.set(i, 1);
		for (int i = 97; i <= 122; ++i) start.set(i, 1);
		for (int i = 48; i <= 48; ++i) start.set(i, 46);
		for (int i = 49; i <= 57; ++i) start.set(i, 47);
		start.set(46, 48); 
		start.set(39, 18); 
		start.set(34, 27); 
		start.set(58, 35); 
		start.set(44, 36); 
		start.set(45, 73); 
		start.set(43, 74); 
		start.set(123, 39); 
		start.set(91, 40); 
		start.set(40, 41); 
		start.set(33, 75); 
		start.set(125, 42); 
		start.set(93, 43); 
		start.set(41, 44); 
		start.set(126, 45); 
		start.set(59, 54); 
		start.set(42, 76); 
		start.set(61, 77); 
		start.set(63, 55); 
		start.set(47, 78); 
		start.set(38, 79); 
		start.set(124, 80); 
		start.set(94, 81); 
		start.set(37, 82); 
		start.set(60, 83); 
		start.set(62, 84); 
		start.set(Buffer.EOF, -1);
		literals.put("boolean", 6);
		literals.put("byte", 7);
		literals.put("char", 8);
		literals.put("class", 9);
		literals.put("double", 10);
		literals.put("false", 11);
		literals.put("final", 12);
		literals.put("float", 13);
		literals.put("int", 14);
		literals.put("long", 15);
		literals.put("new", 16);
		literals.put("null", 17);
		literals.put("short", 18);
		literals.put("static", 19);
		literals.put("super", 20);
		literals.put("this", 21);
		literals.put("true", 22);
		literals.put("void", 23);
		literals.put("package", 39);
		literals.put("import", 41);
		literals.put("public", 43);
		literals.put("protected", 44);
		literals.put("private", 45);
		literals.put("abstract", 46);
		literals.put("strictfp", 47);
		literals.put("native", 48);
		literals.put("synchronized", 49);
		literals.put("transient", 50);
		literals.put("volatile", 51);
		literals.put("extends", 53);
		literals.put("implements", 54);
		literals.put("throws", 55);
		literals.put("interface", 56);
		literals.put("if", 57);
		literals.put("else", 58);
		literals.put("for", 59);
		literals.put("while", 60);
		literals.put("do", 61);
		literals.put("try", 62);
		literals.put("finally", 63);
		literals.put("switch", 64);
		literals.put("return", 65);
		literals.put("throw", 66);
		literals.put("break", 67);
		literals.put("continue", 68);
		literals.put("catch", 69);
		literals.put("case", 70);
		literals.put("default", 71);
		literals.put("instanceof", 73);

	}
	
	public Scanner (String fileName) {
		buffer = new Buffer(fileName);
		Init();
	}
	
	public Scanner(InputStream s) {
		buffer = new Buffer(s);
		Init();
	}
	
	void Init () {
		pos = -1; line = 1; col = 0; charPos = -1;
		oldEols = 0;
		NextCh();
		if (ch == 0xEF) { // check optional byte order mark for UTF-8
			NextCh(); int ch1 = ch;
			NextCh(); int ch2 = ch;
			if (ch1 != 0xBB || ch2 != 0xBF) {
				throw new FatalError("Illegal byte order mark at start of file");
			}
			buffer = new UTF8Buffer(buffer); col = 0; charPos = -1;
			NextCh();
		}
		pt = tokens = new Token();  // first token is a dummy
	}
	
	void NextCh() {
		if (oldEols > 0) { ch = EOL; oldEols--; }
		else {
			pos = buffer.getPos();
			// buffer reads unicode chars, if UTF8 has been detected
			ch = buffer.Read(); col++; charPos++;
			// replace isolated '\r' by '\n' in order to make
			// eol handling uniform across Windows, Unix and Mac
			if (ch == '\r' && buffer.Peek() != '\n') ch = EOL;
			if (ch == EOL) { line++; col = 0; }
		}

	}
	
	void AddCh() {
		if (tlen >= tval.length) {
			char[] newBuf = new char[2 * tval.length];
			System.arraycopy(tval, 0, newBuf, 0, tval.length);
			tval = newBuf;
		}
		if (ch != Buffer.EOF) {
			tval[tlen++] = (char)ch; 

			NextCh();
		}

	}
	

	boolean Comment0() {
		int level = 1, pos0 = pos, line0 = line, col0 = col, charPos0 = charPos;
		NextCh();
		if (ch == '/') {
			NextCh();
			for(;;) {
				if (ch == 10) {
					level--;
					if (level == 0) { oldEols = line - line0; NextCh(); return true; }
					NextCh();
				} else if (ch == Buffer.EOF) return false;
				else NextCh();
			}
		} else {
			buffer.setPos(pos0); NextCh(); line = line0; col = col0; charPos = charPos0;
		}
		return false;
	}

	boolean Comment1() {
		int level = 1, pos0 = pos, line0 = line, col0 = col, charPos0 = charPos;
		NextCh();
		if (ch == '*') {
			NextCh();
			for(;;) {
				if (ch == '*') {
					NextCh();
					if (ch == '/') {
						level--;
						if (level == 0) { oldEols = line - line0; NextCh(); return true; }
						NextCh();
					}
				} else if (ch == Buffer.EOF) return false;
				else NextCh();
			}
		} else {
			buffer.setPos(pos0); NextCh(); line = line0; col = col0; charPos = charPos0;
		}
		return false;
	}


	void CheckLiteral() {
		String val = t.val;

		Object kind = literals.get(val);
		if (kind != null) {
			t.kind = ((Integer) kind).intValue();
		}
	}

	Token NextToken() {
		while (ch == ' ' ||
			ch >= 9 && ch <= 10 || ch == 13
		) NextCh();
		if (ch == '/' && Comment0() ||ch == '/' && Comment1()) return NextToken();
		int recKind = noSym;
		int recEnd = pos;
		t = new Token();
		t.pos = pos; t.col = col; t.line = line; t.charPos = charPos;
		int state = start.state(ch);
		tlen = 0; AddCh();

		loop: for (;;) {
			switch (state) {
				case -1: { t.kind = eofSym; break loop; } // NextCh already done 
				case 0: {
					if (recKind != noSym) {
						tlen = recEnd - t.pos;
						SetScannerBehindT();
					}
					t.kind = recKind; break loop;
				} // NextCh already done
				case 1:
					recEnd = pos; recKind = 1;
					if (ch == '$' || ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'Z' || ch == '_' || ch >= 'a' && ch <= 'z') {AddCh(); state = 1; break;}
					else {t.kind = 1; t.val = new String(tval, 0, tlen); CheckLiteral(); return t;}
				case 2:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 3; break;}
					else {state = 0; break;}
				case 3:
					recEnd = pos; recKind = 2;
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 3; break;}
					else if (ch == 'L' || ch == 'l') {AddCh(); state = 4; break;}
					else {t.kind = 2; break loop;}
				case 4:
					{t.kind = 2; break loop;}
				case 5:
					recEnd = pos; recKind = 3;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 5; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 6; break;}
					else {t.kind = 3; break loop;}
				case 6:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 8; break;}
					else if (ch == '+' || ch == '-') {AddCh(); state = 7; break;}
					else {state = 0; break;}
				case 7:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 8; break;}
					else {state = 0; break;}
				case 8:
					recEnd = pos; recKind = 3;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 8; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 3; break loop;}
				case 9:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 9; break;}
					else if (ch == '.') {AddCh(); state = 10; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 14; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {state = 0; break;}
				case 10:
					recEnd = pos; recKind = 3;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 10; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 11; break;}
					else {t.kind = 3; break loop;}
				case 11:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 13; break;}
					else if (ch == '+' || ch == '-') {AddCh(); state = 12; break;}
					else {state = 0; break;}
				case 12:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 13; break;}
					else {state = 0; break;}
				case 13:
					recEnd = pos; recKind = 3;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 13; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 3; break loop;}
				case 14:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 16; break;}
					else if (ch == '+' || ch == '-') {AddCh(); state = 15; break;}
					else {state = 0; break;}
				case 15:
					if (ch >= '0' && ch <= '9') {AddCh(); state = 16; break;}
					else {state = 0; break;}
				case 16:
					recEnd = pos; recKind = 3;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 16; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 3; break loop;}
				case 17:
					{t.kind = 3; break loop;}
				case 18:
					if (ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '&' || ch >= '(' && ch <= '[' || ch >= ']' && ch <= 65535) {AddCh(); state = 19; break;}
					else if (ch == 92) {AddCh(); state = 20; break;}
					else {state = 0; break;}
				case 19:
					if (ch == 39) {AddCh(); state = 26; break;}
					else {state = 0; break;}
				case 20:
					if (ch >= '0' && ch <= '3') {AddCh(); state = 49; break;}
					else if (ch >= '4' && ch <= '7') {AddCh(); state = 25; break;}
					else if (ch == '"' || ch == 39 || ch == 92 || ch == 'b' || ch == 'f' || ch == 'n' || ch == 'r' || ch == 't') {AddCh(); state = 19; break;}
					else if (ch == 'u') {AddCh(); state = 21; break;}
					else {state = 0; break;}
				case 21:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 22; break;}
					else if (ch == 'u') {AddCh(); state = 21; break;}
					else {state = 0; break;}
				case 22:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 23; break;}
					else {state = 0; break;}
				case 23:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 24; break;}
					else {state = 0; break;}
				case 24:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 19; break;}
					else {state = 0; break;}
				case 25:
					if (ch >= '0' && ch <= '7') {AddCh(); state = 19; break;}
					else if (ch == 39) {AddCh(); state = 26; break;}
					else {state = 0; break;}
				case 26:
					{t.kind = 4; break loop;}
				case 27:
					if (ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '!' || ch >= '#' && ch <= '[' || ch >= ']' && ch <= 65535) {AddCh(); state = 27; break;}
					else if (ch == '"') {AddCh(); state = 34; break;}
					else if (ch == 92) {AddCh(); state = 28; break;}
					else {state = 0; break;}
				case 28:
					if (ch >= '0' && ch <= '3') {AddCh(); state = 51; break;}
					else if (ch >= '4' && ch <= '7') {AddCh(); state = 33; break;}
					else if (ch == '"' || ch == 39 || ch == 92 || ch == 'b' || ch == 'f' || ch == 'n' || ch == 'r' || ch == 't') {AddCh(); state = 27; break;}
					else if (ch == 'u') {AddCh(); state = 29; break;}
					else {state = 0; break;}
				case 29:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 30; break;}
					else if (ch == 'u') {AddCh(); state = 29; break;}
					else {state = 0; break;}
				case 30:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 31; break;}
					else {state = 0; break;}
				case 31:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 32; break;}
					else {state = 0; break;}
				case 32:
					if (ch >= '0' && ch <= '9' || ch >= 'A' && ch <= 'F' || ch >= 'a' && ch <= 'f') {AddCh(); state = 27; break;}
					else {state = 0; break;}
				case 33:
					if (ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '!' || ch >= '#' && ch <= '[' || ch >= ']' && ch <= 65535) {AddCh(); state = 27; break;}
					else if (ch == '"') {AddCh(); state = 34; break;}
					else if (ch == 92) {AddCh(); state = 28; break;}
					else {state = 0; break;}
				case 34:
					{t.kind = 5; break loop;}
				case 35:
					{t.kind = 24; break loop;}
				case 36:
					{t.kind = 25; break loop;}
				case 37:
					{t.kind = 26; break loop;}
				case 38:
					{t.kind = 28; break loop;}
				case 39:
					{t.kind = 29; break loop;}
				case 40:
					{t.kind = 30; break loop;}
				case 41:
					{t.kind = 31; break loop;}
				case 42:
					{t.kind = 35; break loop;}
				case 43:
					{t.kind = 36; break loop;}
				case 44:
					{t.kind = 37; break loop;}
				case 45:
					{t.kind = 38; break loop;}
				case 46:
					recEnd = pos; recKind = 2;
					if (ch >= '0' && ch <= '7') {AddCh(); state = 53; break;}
					else if (ch >= '8' && ch <= '9') {AddCh(); state = 9; break;}
					else if (ch == 'L' || ch == 'l') {AddCh(); state = 4; break;}
					else if (ch == 'X' || ch == 'x') {AddCh(); state = 2; break;}
					else if (ch == '.') {AddCh(); state = 10; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 14; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 2; break loop;}
				case 47:
					recEnd = pos; recKind = 2;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 47; break;}
					else if (ch == 'L' || ch == 'l') {AddCh(); state = 4; break;}
					else if (ch == '.') {AddCh(); state = 10; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 14; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 2; break loop;}
				case 48:
					recEnd = pos; recKind = 27;
					if (ch >= '0' && ch <= '9') {AddCh(); state = 5; break;}
					else {t.kind = 27; break loop;}
				case 49:
					if (ch >= '0' && ch <= '7') {AddCh(); state = 50; break;}
					else if (ch == 39) {AddCh(); state = 26; break;}
					else {state = 0; break;}
				case 50:
					if (ch >= '0' && ch <= '7') {AddCh(); state = 19; break;}
					else if (ch == 39) {AddCh(); state = 26; break;}
					else {state = 0; break;}
				case 51:
					if (ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '!' || ch >= '#' && ch <= '/' || ch >= '8' && ch <= '[' || ch >= ']' && ch <= 65535) {AddCh(); state = 27; break;}
					else if (ch >= '0' && ch <= '7') {AddCh(); state = 52; break;}
					else if (ch == '"') {AddCh(); state = 34; break;}
					else if (ch == 92) {AddCh(); state = 28; break;}
					else {state = 0; break;}
				case 52:
					if (ch <= 9 || ch >= 11 && ch <= 12 || ch >= 14 && ch <= '!' || ch >= '#' && ch <= '[' || ch >= ']' && ch <= 65535) {AddCh(); state = 27; break;}
					else if (ch == '"') {AddCh(); state = 34; break;}
					else if (ch == 92) {AddCh(); state = 28; break;}
					else {state = 0; break;}
				case 53:
					recEnd = pos; recKind = 2;
					if (ch >= '0' && ch <= '7') {AddCh(); state = 53; break;}
					else if (ch >= '8' && ch <= '9') {AddCh(); state = 9; break;}
					else if (ch == 'L' || ch == 'l') {AddCh(); state = 4; break;}
					else if (ch == '.') {AddCh(); state = 10; break;}
					else if (ch == 'E' || ch == 'e') {AddCh(); state = 14; break;}
					else if (ch == 'D' || ch == 'F' || ch == 'd' || ch == 'f') {AddCh(); state = 17; break;}
					else {t.kind = 2; break loop;}
				case 54:
					{t.kind = 40; break loop;}
				case 55:
					{t.kind = 72; break loop;}
				case 56:
					{t.kind = 74; break loop;}
				case 57:
					{t.kind = 75; break loop;}
				case 58:
					{t.kind = 76; break loop;}
				case 59:
					{t.kind = 77; break loop;}
				case 60:
					{t.kind = 78; break loop;}
				case 61:
					{t.kind = 79; break loop;}
				case 62:
					{t.kind = 80; break loop;}
				case 63:
					{t.kind = 81; break loop;}
				case 64:
					{t.kind = 82; break loop;}
				case 65:
					{t.kind = 83; break loop;}
				case 66:
					{t.kind = 84; break loop;}
				case 67:
					{t.kind = 85; break loop;}
				case 68:
					{t.kind = 86; break loop;}
				case 69:
					{t.kind = 90; break loop;}
				case 70:
					{t.kind = 91; break loop;}
				case 71:
					{t.kind = 94; break loop;}
				case 72:
					{t.kind = 95; break loop;}
				case 73:
					recEnd = pos; recKind = 32;
					if (ch == '-') {AddCh(); state = 37; break;}
					else if (ch == '=') {AddCh(); state = 57; break;}
					else {t.kind = 32; break loop;}
				case 74:
					recEnd = pos; recKind = 34;
					if (ch == '+') {AddCh(); state = 38; break;}
					else if (ch == '=') {AddCh(); state = 56; break;}
					else {t.kind = 34; break loop;}
				case 75:
					recEnd = pos; recKind = 33;
					if (ch == '=') {AddCh(); state = 70; break;}
					else {t.kind = 33; break loop;}
				case 76:
					recEnd = pos; recKind = 42;
					if (ch == '=') {AddCh(); state = 58; break;}
					else {t.kind = 42; break loop;}
				case 77:
					recEnd = pos; recKind = 52;
					if (ch == '=') {AddCh(); state = 69; break;}
					else {t.kind = 52; break loop;}
				case 78:
					recEnd = pos; recKind = 99;
					if (ch == '=') {AddCh(); state = 59; break;}
					else {t.kind = 99; break loop;}
				case 79:
					recEnd = pos; recKind = 89;
					if (ch == '=') {AddCh(); state = 60; break;}
					else if (ch == '&') {AddCh(); state = 68; break;}
					else {t.kind = 89; break loop;}
				case 80:
					recEnd = pos; recKind = 87;
					if (ch == '=') {AddCh(); state = 61; break;}
					else if (ch == '|') {AddCh(); state = 67; break;}
					else {t.kind = 87; break loop;}
				case 81:
					recEnd = pos; recKind = 88;
					if (ch == '=') {AddCh(); state = 62; break;}
					else {t.kind = 88; break loop;}
				case 82:
					recEnd = pos; recKind = 100;
					if (ch == '=') {AddCh(); state = 63; break;}
					else {t.kind = 100; break loop;}
				case 83:
					recEnd = pos; recKind = 92;
					if (ch == '<') {AddCh(); state = 85; break;}
					else if (ch == '=') {AddCh(); state = 71; break;}
					else {t.kind = 92; break loop;}
				case 84:
					recEnd = pos; recKind = 93;
					if (ch == '>') {AddCh(); state = 86; break;}
					else if (ch == '=') {AddCh(); state = 72; break;}
					else {t.kind = 93; break loop;}
				case 85:
					recEnd = pos; recKind = 96;
					if (ch == '=') {AddCh(); state = 64; break;}
					else {t.kind = 96; break loop;}
				case 86:
					recEnd = pos; recKind = 97;
					if (ch == '=') {AddCh(); state = 65; break;}
					else if (ch == '>') {AddCh(); state = 87; break;}
					else {t.kind = 97; break loop;}
				case 87:
					recEnd = pos; recKind = 98;
					if (ch == '=') {AddCh(); state = 66; break;}
					else {t.kind = 98; break loop;}

			}
		}
		t.val = new String(tval, 0, tlen);
		return t;
	}
	
	private void SetScannerBehindT() {
		buffer.setPos(t.pos);
		NextCh();
		line = t.line; col = t.col; charPos = t.charPos;
		for (int i = 0; i < tlen; i++) NextCh();
	}
	
	// get the next token (possibly a token already seen during peeking)
	public Token Scan () {
		if (tokens.next == null) {
			return NextToken();
		} else {
			pt = tokens = tokens.next;
			return tokens;
		}
	}

	// get the next token, ignore pragmas
	public Token Peek () {
		do {
			if (pt.next == null) {
				pt.next = NextToken();
			}
			pt = pt.next;
		} while (pt.kind > maxT); // skip pragmas

		return pt;
	}

	// make sure that peeking starts at current scan position
	public void ResetPeek () { pt = tokens; }

} // end Scanner
