package com.itahm.servlet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
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
	
	private byte [] event = null;
	
	@Override
	public void init(ServletConfig config) {
		System.setErr(
			new PrintStream(
				new OutputStream() {

					@Override
					public void write(int b) throws IOException {
					}	
				}
			) {
		
				@Override
				public void print(Object e) {
					((Exception)e).printStackTrace(System.out);
				}
			}
		);
		
		String value = config.getInitParameter("license");
		
		if (value != null) {
			try {
				long l = Long.parseLong(value, 16);
				byte [] license = new byte[6];
				
				for (int i=6; i>0; l>>=8) {
					license[--i] = (byte)(0xff & l);
				}
				
				if (!Agent.isValidLicense(license)) {
					System.out.println("Check your License.MAC");
					
					return;
				}
			} catch (NumberFormatException nfe) {}
		}
		
		value = config.getInitParameter("expire");
		
		if (value != null) {
			try {
				long expire = Long.parseLong(value);
				
				if (Calendar.getInstance().getTimeInMillis() > expire) {
					System.out.println("Check your License.Expire");
					
					return;
				}
	
				new Timer().schedule(new TimerTask() {
					
					@Override
					public void run() {
						System.out.println("Check your License.Expire");
						
						destroy();
					}
				}, new Date(expire));
				
				Agent.Config.expire(expire);
			} catch (NumberFormatException nfe) {}
		}
		
		value = config.getInitParameter("limit");
		
		if (value != null) {
			try {
				int limit = Integer.parseInt(value);
				
				Agent.Config.limit(limit);
			} catch (NumberFormatException nfe) {}
		}
		
		value = config.getInitParameter("root");
		
		if (value != null) {
			File root = new File(value);
			
			if (!root.isDirectory()) {
				System.out.println("Check your Configuration.Root");
				
				return;
			}
		
			System.out.format("Root : %s\n", root.getAbsoluteFile());
			
			Agent.Config.root(root);
		}
		else {
			System.out.println("Check your Configuration.Root");
			
			return;
		}
		
		System.out.format("Agent loading...\n");
		
		Agent.Config.listener(this);
		
		try {	
			Agent.start();
		} catch (IOException ioe) {
			System.err.print(ioe);
			
			return;
		}
	
		System.out.println("ITAhM agent has been successfully started.");
	}
	
	@Override
	public void destroy() {
		try {
			Agent.stop();
		} catch (IOException ioe) {
			System.err.print(ioe);
		}
	}
	
	@Override
	public void doPost(HttpServletRequest request, HttpServletResponse response) {
		String origin = request.getHeader("origin");
		
		if (origin != null) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
		}
		
		if (!Agent.ready) {
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			
			return;
		}
		
		
		int cl = request.getContentLength();
		
		
		if (cl < 0) {
			response.setStatus(HttpServletResponse.SC_LENGTH_REQUIRED);
		}
		else {
			byte [] buffer = new byte [cl];
			JSONObject data;
			
			try (InputStream is = request.getInputStream()) {
				for (int i=0; i<cl;) {
					i += is.read(buffer, i, cl - i);
					if (i < 0) {
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
						
						if (data.has("event")) {
							event = Agent.getEvent(data.getString("event"));
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
