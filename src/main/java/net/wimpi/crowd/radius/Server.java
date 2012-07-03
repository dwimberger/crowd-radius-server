package net.wimpi.crowd.radius;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.util.*;

/**
 * Main class that allows to configure and start the radius server.
 *
 * @author Dieter Wimberger (dieter@wimpi.net).
 */
public class Server {

  private static final Logger log = LoggerFactory.getLogger(Server.class);
  private static ResourceBundle c_ResourceBundle = ResourceBundle.getBundle("net.wimpi.crowd.radius.strings");

  public static void main(String[] args) {
    try {
      RadiusService rs = new RadiusService();
      Properties p = new Properties();
      p.load(new FileReader(args[0]));
      rs.configure(p);
      rs.start();

    } catch (Exception ex) {
      ex.printStackTrace(System.out);
    }
  }//main

}//class Server
