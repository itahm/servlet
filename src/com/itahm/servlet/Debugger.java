package com.itahm.servlet;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@SuppressWarnings("serial")
public class Debugger extends HttpServlet {

	private File root;
	
	@Override
	public void init(ServletConfig config) {
		this.root = new File(new File(new File(config.getInitParameter("path")), "data"), "debug");
		
		this.root.mkdirs();
	}
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "http://www.itahm.com");
		response.setHeader("Access-Control-Allow-Credentials", "true");
		
		int cl = request.getContentLength();
		
		if (cl > 0) {
			byte [] buffer = new byte [cl];
			
			try (InputStream is = request.getInputStream()) {
				for (int i=0; i<cl;) {
					i += is.read(buffer, i, cl - i);
				}
		
				try (FileWriter fw = new FileWriter(new File(this.root, getDateString()), true)) {
					fw.write(request.getRemoteAddr());
					fw.write(System.getProperty("line.separator"));
					fw.write(new String(buffer, StandardCharsets.UTF_8.name()));
					fw.write(System.getProperty("line.separator"));
					
					fw.flush();
				} catch (IOException ioe) {
					System.err.print(ioe);
				}
			} catch (IOException ioe) {
				System.err.print(ioe);
			}
		}
	}
	
	private String getDateString() {
		Calendar c = Calendar.getInstance();
		
		return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) +1, c.get(Calendar.DAY_OF_MONTH));
	}

}
