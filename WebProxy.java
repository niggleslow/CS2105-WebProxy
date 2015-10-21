/*

Author: Nicholas Low Jun Han (A0110574N)
Assignment 1: HTTP Proxy

This proxy is coded to meet the following criteria as specified in the PDF:

BASIC CRITERIA (met all of them):

1. Handle small web objects like a small file or image
2. Handle complex web pages with multiple objects
3. Handle very large files of up to 1 GB
4. Handle erroneous requests such as a “404” response and "502" response
	- Prints error message with Error 502 code on console for 502 errors
5. Handle the POST method in addition to GET method 
	- NOTE: For some reason it takes a little bit of time before server replies, but reply is accurate according to testing done
6. Handle web requests to a specified port
	- Tested using: http://portquiz.net:8080/ 

ADVANCED CRITERIA

1. Multi-threading
2. Simple caching

*/

import java.net.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebProxy {

	public static HashMap<String, String> cache;
	private static ServerSocket socket;
	private static int port;
	protected ExecutorService executor;

	public WebProxy(int port) {
		executor = Executors.newCachedThreadPool();
		try {
			socket = new ServerSocket(port);
		} catch(IOException ioe) {
			ioe.printStackTrace();
		}
	}

	public void accept() {
		while (true) {
			try {
				executor.execute(new RequestHandler(socket.accept(), cache));
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
	}

	public static void main(String[] args) {
		port = Integer.parseInt(args[0]);
		cache = new HashMap<String,String>();
		System.out.println("WebProxy is listening to port " + port);
		WebProxy proxy = new WebProxy(port);
		proxy.accept();
	}
}

class RequestHandler implements Runnable {

	private Socket client;
	private HashMap<String,String> cache;
	
	public RequestHandler(Socket clientSocket, HashMap<String,String> cache) {
		this.client = clientSocket;
		this.cache = cache;
	}

	public void run() {
		try {
			System.out.println("----------- BEGIN -----------");
			BufferedOutputStream outToClient = new BufferedOutputStream(client.getOutputStream());

			//Setting up the cache
			createCacheDirectory();

			//Receive and Create HTTPRequest object based on CLIENT's Input
			HTTPRequest clientReq = createHTTPReq(client);
			System.out.println("Client's request received and processed:\n" + clientReq.getReqHeader().getStringHeader());

			//Extract and Establish SERVER
			int serverPort = 80;
			if(!clientReq.getReqHeader().getPort().equals("")) {
				serverPort = Integer.parseInt(clientReq.getReqHeader().getPort());
			}
			System.out.println("TRYING TO ESTABLISH CONNECTION FOR " + clientReq.getReqHeader().getHost() + " AT PORT " + serverPort);
			System.out.println(clientReq.getReqHeader().getHost());
			try {
				Socket server = new Socket(clientReq.getReqHeader().getHost(), serverPort);
				System.out.println("SUCCESSFUL CONNECTION: " + clientReq.getReqHeader().getHost() + " at PORT: " + serverPort + "\n");

				//Check which method is being requested by CLIENT
				if(clientReq.getReqHeader().isGet()) {
					System.out.println("Handling GET Request");
					if(isCached(clientReq.getReqHeader().getRequest())) {
						System.out.println("Cached copy already exists");
						System.out.println("Returning cached copy to client");
						getFromCache(clientReq.getReqHeader().getRequest(), outToClient);
					}
					else {
						System.out.println("Requested page not found in cache");
						System.out.println("Proceeding with cache and returning to CLIENT");
						sendRequestToServer(clientReq, server, 0);
						try {
							cacheAndReturn(clientReq.getReqHeader().getRequest(), server, outToClient);
						} catch (Exception e) {

						}
					}
				}
				else {
					System.out.println("Handling POST Request");
					sendRequestToServer(clientReq, server, 1); //1 since it is POST request
					sendResponseToClient(server, outToClient);
				}

				//Close CLIENT and SERVER sockets
				server.close();
				client.close();
				System.out.println("--------- Request Successfully Completed ---------");
			}
			catch (UnknownHostException unk) {
				System.out.println("ERROR 502: Cannot reach server");
				client.close();
				System.out.println("--- Request Terminated --- \n");
			}
		}
		catch (IOException ioe) {
				ioe.printStackTrace();
		}
	}

	private static void sendRequestToServer(HTTPRequest clientReq, Socket server, int getOrPost) throws IOException {
		BufferedOutputStream outToServer = new BufferedOutputStream(server.getOutputStream());
		//	GET
		if(getOrPost == 0) {
			byte[] byteHeader = clientReq.getReqHeader().getByteHeader();
			outToServer.write(byteHeader, 0, byteHeader.length);	
		}
		//	POST
		else {
			String eof = "\n";
			outToServer.write(clientReq.getReqHeader().getByteHeader());
			byte[] body = clientReq.getReqBody().getBytes(Charset.forName("UTF-8"));
			outToServer.write(body, 0, body.length);
			outToServer.write(eof.getBytes(Charset.forName("UTF-8")));
		}

		//Flush the stream to make sure no data is left in buffer
		outToServer.flush();
	}

	//	Method handles the sending of SERVER's RESPONSE to CLIENT
	private static void sendResponseToClient(Socket server, BufferedOutputStream outToClient) throws IOException{
		int bytes_read;
		byte[] buffer = new byte[8096];
		BufferedInputStream inFromServer = new BufferedInputStream(server.getInputStream());
		while((bytes_read = inFromServer.read(buffer)) != -1) {
			outToClient.write(buffer, 0, bytes_read);
		}
		outToClient.flush();
	}

	//	Method creates a HTTPReq object by processing client's input stream
	private static HTTPRequest createHTTPReq(Socket client) throws IOException {
		BufferedReader inputFromClient = new BufferedReader(new InputStreamReader(client.getInputStream()));
		return new HTTPRequest(inputFromClient);
	}

	//	Method returns cached file and returns it to the CLIENT
	private void getFromCache(String address, BufferedOutputStream toClient) throws FileNotFoundException {
		String reqAddress = this.cache.get(address);
		BufferedInputStream fileStream = new BufferedInputStream(new FileInputStream(reqAddress));
		try {
			int bytes_read;
			byte[] buffer = new byte[2048];
			while((bytes_read = fileStream.read(buffer)) != -1) {
				System.out.println("----------- Transferring " + bytes_read + " from cache to client -----------");
				toClient.write(buffer, 0, bytes_read);
			}
			toClient.flush();
		}
		catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	//	Method caches the SERVER's response to local file and return to CLIENT
	private void cacheAndReturn(String address, Socket server, BufferedOutputStream outToClient) throws Exception {
		File localFile = createFile(address);
		BufferedOutputStream fileStream = new BufferedOutputStream(new FileOutputStream(localFile));
		BufferedInputStream inFromServer = new BufferedInputStream(server.getInputStream());

		int bytes_read;
		byte[] buffer = new byte[2048];

		while((bytes_read = inFromServer.read(buffer)) != -1) {
			fileStream.write(buffer, 0, bytes_read);
			outToClient.write(buffer, 0, bytes_read);
		}
		fileStream.flush();
		outToClient.flush();

		fileStream.close();
	}

	//	Method creates cache directory in local memory if it does not already exist
	private static void createCacheDirectory() {
		File cacheDirectory = new File("ProxyCache");
		if(!cacheDirectory.exists()) {
			cacheDirectory.mkdir();
			System.out.println("----------- Cache directory created with name: ProxyCache -----------");
		}
	}

	//	Method creates file to store the contents of <address> in cache directory
	private File createFile(String address) throws IOException {
		String cacheFileAddress = "ProxyCache/" + address.hashCode();

		this.cache.put(address, cacheFileAddress);
		File cacheFile = new File(cacheFileAddress);

		cacheFile.createNewFile();
		return cacheFile;
	}

	//	Method checks whether GET request is already in cache
	private boolean isCached(String address) {
		return this.cache.containsKey(address);
	}
}

/*
CLASS CREATED TO OBJECTIFY THE CLIENT'S REQUEST FOR EASIER ACCESS
Contains:
- RequestHeader
- RequestBody (Uses a Scanner to read)
*/
class HTTPRequest {

	//Data members of HTTPRequest object
	private RequestHeader requestHeader;
	private String requestBody;
	private static BufferedReader input;

	//Constructor of HTTPRequest
	public HTTPRequest(BufferedReader input) throws IOException{
		this.input = input;
		extractHeaderAndBody(input);
	}

	//Getters
	public RequestHeader getReqHeader() {
		return this.requestHeader;
	}

	public String getReqBody() {
		return this.requestBody;
	}

	//Setters
	//	Method to extract header from client's input
	private void extractHeaderAndBody(BufferedReader input)  throws IOException{
		String header = "";
		String body = "";
		String currentLine = null;
		int contentLength = 0;
		//reads until empty line detected
		while((currentLine = input.readLine()).length() > 0) {
			header += currentLine + "\n";
			if(currentLine.startsWith("Content-Length:")) {
				contentLength = Integer.parseInt(currentLine.substring(currentLine.indexOf(':') + 2));
			}
		}
		header += "\n";
		this.requestHeader = new RequestHeader(header);
		if(header.startsWith("POST")) {
			char[] postData = new char[contentLength];
			input.read(postData);
			body = new String(postData);
		}
		this.requestBody = body;
	}
}


//CLASS CREATED FOR CONVENIENCE OF REFERENECES TO THE HEADERS OF CLIENT'S REQUEST
class RequestHeader {

	//Data members of RequestHeader object
	private String headerString;
	private ArrayList<String> headerArray;

	//Constructor of RequestHeader object
	public RequestHeader(String header) {
		this.headerString = header;
		this.headerArray = stringToArrayList(headerString);
	}

	//Converts the Request Header from String to ArrayList form for easier access
	private ArrayList<String> stringToArrayList(String headerString) {
		String[] tmp = headerString.split("\n");
		ArrayList<String> headerArray = new ArrayList<String>();
		for(int i = 0; i < tmp.length; i++) {
			headerArray.add(tmp[i]);
		}
		return headerArray;
	}

	//Gets the request URI
	public String getRequest() {
		return this.headerArray.get(0).split(" ")[1];
	}

	//Gets the method type (GET, POST supported) of the Client's REQUEST
	public String getMethod() {
		String[] tmp = this.headerArray.get(0).split(" ");
		String method = tmp[0].toUpperCase();
		//System.out.println(method);
		return method;
	}

	//Gets content-length (FOR POST method)
	public int getContentLength() {
		String contentLengthS = "";
		String current;
		for(int i = 0; i < this.headerArray.size(); i++) {
			current = this.headerArray.get(i);
			if(current.toUpperCase().startsWith("CONTENT-LENGTH:")) {
				contentLengthS = (current.split(":")[1].trim());
			}
		}
		return Integer.parseInt(contentLengthS);
	} 

	//Gets the port number of server if it was specified, else return -1
	public String getPort() {
		String[] tmp = this.headerArray.get(0).split(" ");
		String portNumber = "";
		String uri = tmp[1];
		uri = uri.substring(uri.indexOf(':') + 1); 
		if(uri.indexOf(':') != -1) {
			portNumber = uri.substring(uri.indexOf(':') + 1);
		}
		return portNumber;
	}

	//Get the header in BYTE[] form (FOR WRITING ONTO SERVER)
	public byte[] getByteHeader() {
		return this.headerString.getBytes(Charset.forName("UTF-8"));
	}

	//Gets the Host to set up SERVER's socket
	public String getHost() {
		String host = "";
		String current;
		for(int i = 0; i < this.headerArray.size(); i++) {
			current = this.headerArray.get(i);
			if(current.toUpperCase().startsWith("HOST:")) {
				host = (current.split(":")[1]).trim();
			}
		}
		//System.out.println("Host as extracted from getHost() method is: " + host);
		return host;
	}

	//Gets the headerString (FOR CHECKING PURPOSES)
	public String getStringHeader() {
		return this.headerString;
	}

	//Check if request is of GET|POST type
	public boolean isGet() {
		return this.getMethod().equals("GET");
	}

	public boolean isPost() {
		return this.getMethod().equals("POST");
	}
}