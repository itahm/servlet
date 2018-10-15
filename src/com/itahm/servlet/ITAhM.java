package com.itahm.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletConfig;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.HTTPListener;
import com.itahm.http.Response;

@SuppressWarnings("serial")
public class ITAhM extends HttpServlet implements HTTPListener {
	
	private boolean isClosed = true;
	private byte [] event = null;
	
	@Override
	public void init(ServletConfig config) {
		byte [] license = null; // new byte [] {(byte)0x6c, (byte)0x3b, (byte)0xe5, (byte)0x51, (byte)0x2D, (byte)0x80};
		long expire = 0; // 1546268400000L;
		int limit = 0;
		
		try {
			if (!Agent.isValidLicense(license)) {
				System.out.println("Check your License[1].");
				
				return;
			}
		} catch (SocketException se) {
			se.printStackTrace();
			
			return;
		}
		
		File root;
		try {
			root = new File(config.getInitParameter("path"));
			
			if (!root.isDirectory()) {
				throw new NullPointerException();
			}
		}
		catch (NullPointerException npe) {
			System.out.println("Root not found.");
			
			return;
		}
		
		System.out.format("Root : %s\n", root.getAbsoluteFile());
		
		if (expire > 0) {
			if (Calendar.getInstance().getTimeInMillis() > expire) {
				System.out.println("Check your License[2].");
				
				return;
			}

			new Timer().schedule(new TimerTask() {
				
				@Override
				public void run() {
					Agent.close();
					
					System.out.println("Check your License[3].");
				}
			}, new Date(expire));
		}
		
		System.out.format("Agent loading...\n");
		
		try {
			Agent.initialize(root);
		} catch (IOException ioe) {
			ioe.printStackTrace();
			
			return;
		}
		
		Agent.setLimit(limit);
		Agent.setExpire(expire);
		Agent.setListener(this);
		
		try {	
			Agent.start();
		} catch (IOException ioe) {
			ioe.printStackTrace();
			
			return;
		}
	
		System.out.println("ITAhM agent has been successfully started.");
		
		isClosed = false;
	}
	
	@Override
	public void destroy() {
		this.isClosed = true;
		
		Agent.close();
	}
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) {
		if (this.isClosed) {
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			
			return;
		}
		
		String origin = request.getHeader("origin");
		int cl = request.getContentLength();
		
		if (origin != null) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
		}
		
		if (cl < 0) {
			response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
		}
		else {
			byte [] buffer = new byte [cl];
			JSONObject data;
			
			try (InputStream is = request.getInputStream()) {
				for (int i=0, read; i<cl; i++) {
					read = is.read(buffer, i, cl - i);
					if (read < 0) {
						break;
					}
				}
			
				data = new JSONObject(new String(buffer, StandardCharsets.UTF_8.name()));
	
				if (!data.has("command")) {
					throw new JSONException("Command not found.");
				}
				
				HttpSession session = request.getSession(false);
				
				switch (data.getString("command").toLowerCase()) {
				case "signin":
					JSONObject account = null;
					
					if (session == null) {
						account = Agent.signIn(data);
						
						if (account == null) {
							response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
						}
						else {
							session = request.getSession();
						
							session.setAttribute("account", account);
							session.setMaxInactiveInterval(60 * 60);
						}
					}
					else {
						account = (JSONObject)session.getAttribute("account");
					}
					
					if (account != null) {
						try (ServletOutputStream sos = response.getOutputStream()) {
							sos.write(account.toString().getBytes(StandardCharsets.UTF_8.name()));
							sos.flush();
						}
					}
					
					break;
					
				case "signout":
					if (session != null) {
						session.invalidate();
					}
					
					break;
					
				case "listen":
					if (session == null) {
						response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					}
					else {
						JSONObject event = null;
						
						if (data.has("index")) {
							event = Agent.getEvent(data.getLong("index"));
							
						}
						
						if (event == null) {
							synchronized(this) {
								try {
									wait();
								} catch (InterruptedException ie) {
								}
								
								if (this.event != null) {
									try (ServletOutputStream sos = response.getOutputStream()) {
										sos.write(this.event);
										sos.flush();
									}
								}
							}
						}
						else {
							try (ServletOutputStream sos = response.getOutputStream()) {
								sos.write(event.toString().getBytes(StandardCharsets.UTF_8.name()));
								sos.flush();
							}
						}
					}
					
					break;
					
				default:
					if (session == null) {
						response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					}
					else {
						Response itahmResponse = new Response();
						
						if (!Agent.request(data, itahmResponse)) {
							throw new JSONException("Command not valid.");
						};
						
						int status = itahmResponse.getStatus().getCode();
						
						if (status == 200) {
							try (ServletOutputStream sos = response.getOutputStream()) {
								sos.write(itahmResponse.read());
								sos.flush();
							}
						}
						else {
							response.setStatus(status);
						}
					}
				}
			} catch (JSONException | UnsupportedEncodingException e) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				
				try (ServletOutputStream sos = response.getOutputStream()) {
					sos.write(new JSONObject().
						put("error", e.getMessage()).
						toString().
						getBytes(StandardCharsets.UTF_8.name()));
					
					sos.flush();
				} catch (IOException ioe) {
					ioe.printStackTrace();
				}  				
			} catch (IOException ioe) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				
				try (ServletOutputStream sos = response.getOutputStream()) {
					sos.write(new JSONObject().
						put("error", ioe.getMessage()).
						toString().
						getBytes(StandardCharsets.UTF_8.name()));
					
					sos.flush();
				} catch (IOException ioe2) {
					ioe2.printStackTrace();
				}
			}
		}
	}

	@Override
	public void sendEvent(JSONObject event, boolean broadcast) {
		synchronized(this) {
			try {
				this.event = event.toString().getBytes(StandardCharsets.UTF_8.name());
				
				notifyAll();
			} catch (UnsupportedEncodingException e) {}
		}
		
		if (broadcast) {
			// TODO customize for sms or app
		}
	}
}
