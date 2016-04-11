package org.myrobotlab.codec;

import org.myrobotlab.logging.Level;
import org.myrobotlab.logging.Logging;
import org.myrobotlab.logging.LoggingFactory;

public class CodecCli  {
	
	// command keywords
	public final static String cd = "cd";
	public final static String pwd = "pwd";
	public final static String ls = "ls";
	public final static String help = "help";
	public final static String question = "?";
	
	// set format
	
	
	// encoding type
	
	// decoding type
	
	String cwd = "/";
	String prompt = "#";

	public void process(String line){
		// trim
		String cmd = line.trim();
		
	}

	public static void main(String[] args) {

		LoggingFactory.getInstance().configure();
		LoggingFactory.getInstance().setLevel(Level.INFO);

		try {
			
			CodecCli cli = new CodecCli();
			cli.process("help");
			
			
		} catch (Exception e) {
			Logging.logError(e);
		}

	}

}