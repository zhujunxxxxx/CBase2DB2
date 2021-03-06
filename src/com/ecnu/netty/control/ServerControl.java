package com.ecnu.netty.control;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;

import com.ecnu.model.PullConstant;
import com.ecnu.netty.model.InvokeFuture;
import com.ecnu.netty.model.PullRequest;
import com.ecnu.netty.model.PullResponse;
import com.ecnu.serialize.RpcDecoder;
import com.ecnu.serialize.RpcEncoder;
import com.ecnu.tool.LoggerTool;
import com.ecnu.tool.ResponseBuildler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * 代理客户端
 * @author zhujun
 *
 */
public class ServerControl {
	
	private InetSocketAddress inetAddr;
	
	private volatile Channel channel;
	
	private Map<String, InvokeFuture<Object>> futrues=new ConcurrentHashMap<String, InvokeFuture<Object>>();
	//与Server连接数组
	private Map<String, Channel> channels=new ConcurrentHashMap<String, Channel>();
	
	private Bootstrap bootstrap;
	
	private long timeout=10000;//默认超时
	
	private boolean connected=false;
	
	private boolean isInitialize = false;
	
	private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	
	ServerControl() {
		
	}
	public ServerControl(String host,int port) {
		inetAddr=new InetSocketAddress(host,port);
	}

	private Channel getChannel(String key) {
		return channels.get(key);
	}
	public void init() {
		if(!isInitialize) {
			try {
				final CommonRequestHandler requestHandler = new CommonRequestHandler(this);
				EventLoopGroup group = new NioEventLoopGroup();
	            bootstrap = new Bootstrap();
	            bootstrap.group(group).channel(NioSocketChannel.class)
	                .handler(new ChannelInitializer<SocketChannel>() {
	                    @Override
	                    public void initChannel(SocketChannel channel) throws Exception {
	                    	channel.pipeline().addLast(new RpcDecoder(PullRequest.class));
	                    	channel.pipeline().addLast(new RpcEncoder(PullResponse.class));
	                        channel.pipeline().addLast(requestHandler);
	                    }
	                })
	                .option(ChannelOption.SO_KEEPALIVE, true);
	            this.isInitialize = true;
	        } catch (Exception ex) {
	        	ex.printStackTrace();
	        }
		}
	}

	public void connect() {
		init();
		try {
			ChannelFuture future = bootstrap.connect(this.inetAddr).sync();
			this.channel = future.channel();
			channels.put(this.inetAddr.toString(), future.channel()); 
			connected=true;
			
			LoggerTool.info("connected", (new Throwable()).getStackTrace());
			//发送验证登录信息
			PullResponse authReponse = ResponseBuildler.buildAuth(this.inetAddr.toString());
			authReponse.setFlag(PullConstant.CONTROL_CLEINT);
			Send(authReponse, false);
			LoggerTool.info("auth sucess!", (new Throwable()).getStackTrace());
			
			//心跳线程
			ScheduledExecutorService timerServer = Executors.newScheduledThreadPool(1);
		    timerServer.scheduleWithFixedDelay(new Runnable() {
				@Override
				public void run() {
					//LoggerTool.info("send heart beat.", new Throwable().getStackTrace());
					sendNoReturn(ResponseBuildler.buildHeartBeat("control"));
				}
			}, 10, 30, TimeUnit.SECONDS);
			
		    //直接显示日志
		    PullResponse tmpCtrl1 = ResponseBuildler.buildControl(PullConstant.CONTROL_SHOW_LOG, "true");
		    PullResponse tmpCtrl2 = ResponseBuildler.buildControl(PullConstant.CONTROL_SHOW_LOG, "false");
			Send(tmpCtrl2, false);
			Scanner sc = new Scanner(System.in);
			
			int emptyLine = 0;
			boolean showLog = false;
			while(true) {
				System.out.print("Input Command >:");
				String line = sc.nextLine();
				if(line.equals("exit")) {
					break;
				}
				if(line.length() <= 0) {
					emptyLine ++;
					if(emptyLine > 4) {
						if(showLog) {
							Send(tmpCtrl2, false);
							showLog = false;
						} else {
							Send(tmpCtrl1, false);
							showLog = true;
						}
						emptyLine = 0;
					}
					continue;
				}
				String[] arr = line.split(" ");
				emptyLine = 0;
				
				String op = arr[0];
				
				if(op.equalsIgnoreCase("LEVEL")) {
					if(arr.length < 2) {
						System.out.println("command error!");
						continue;
					}
					String value = arr[1];
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_SET_LEVEL, value);
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				
				else if(op.equalsIgnoreCase("COUNT")) {
					if(arr.length < 2) {
						System.out.println("command error!");
						continue;
					}
					String value = arr[1];
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_SET_COUNT, value);
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				
				else if(op.equalsIgnoreCase("DELAY")) {
					if(arr.length < 2) {
						System.out.println("command error!");
						continue;
					}
					String value = arr[1];
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_SET_DELAY, value);
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("STAT")) {
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_STAT_REPORT, "");
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println("\033[35m"+ new String(ret.getExtend()) + "\033[0m");
				}
				else if(op.equalsIgnoreCase("SHOW")) {
					if(arr.length < 2) {
						System.out.println("command error!");
						continue;
					}
					String value = arr[1];
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_SHOW_LOG, value);
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("DUMP")) {
					if(arr.length < 2) {
						System.out.println("command error!");
						continue;
					}
					String value = arr[1];
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_DUMP_SCHEMA, value);
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("STOP")) {
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_STOP, "");
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("GOON")) {
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_GOON, "");
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("KILL")) {
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_KILL, "");
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("SWITCH")) {
					PullResponse ctrl = ResponseBuildler.buildControl(PullConstant.CONTROL_SWITCH, "");
					PullRequest ret = (PullRequest)Send(ctrl, false);
					System.out.println(new String(ret.getExtend()));
				}
				else if(op.equalsIgnoreCase("HELP")) {
					
					StringBuilder help = new StringBuilder();
					System.out.println();
					help.append(" support command:");
					help.append("\tSTAT | show CDC Server detail info.\n");
					help.append("\tLEVEL (NUMBER) | change CDC Server log level.\n");
					help.append("\tDELAY (NUMBER) | change CDC pull request delay.\n");
					help.append("\tCOUNT (NUMBER) | change log agent read log count.\n");
					help.append("\tSTOP | stop cdc work,not kill.\n");
					help.append("\tGOON | continue cdc work.\n");
					help.append("\tKILL | kill cdc server after 10 seconds.\n");
					help.append("\tSWITCH | switch new file to write.\n");
					System.out.println(help.toString());
				}
				else {
					System.out.println("COMMAN NOT SUPPORT");
				}
				
				
				
			}
			sc.close();
			//future.channel().closeFuture().sync();
			
		} catch(InterruptedException e) {
			e.printStackTrace();
		} finally {
			channels.clear();
			futrues.clear();
			channel = null;
			connected = false;
			executor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						TimeUnit.SECONDS.sleep(5);
						try {
							//重连操作
							LoggerTool.info("reconnect server", new Throwable().getStackTrace());
							connect();
						} catch (Exception ex) {
							
						}
					} catch (Exception ex) {
						
					}
				}
			});
			
		}
	}

	@Deprecated
	public void connect(String host, int port) {
		ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
		future.addListener(new ChannelFutureListener(){
			@Override
			public void operationComplete(ChannelFuture cfuture) throws Exception {
				 Channel channel = cfuture.channel();
				 //添加进入连接数组
			     channels.put(channel.remoteAddress().toString(), channel); 
			}
		});
	}

	@Deprecated
	public Object Send(PullResponse response) {//同步发送消息给服务器
		if(channel==null)
			channel=getChannel(inetAddr.toString());
		if(channel!=null) {	
			final InvokeFuture<Object> future=new InvokeFuture<Object>();
			futrues.put(String.valueOf(response.getSno()), future);
			//设置这次请求的ID
			future.setRequestId(String.valueOf(response.getSno()));
			ChannelFuture cfuture=channel.writeAndFlush(response);
			cfuture.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture rfuture) throws Exception {
					if(!rfuture.isSuccess()){
						future.setCause(rfuture.cause());
					}
				}
			});
			try {
				Object result=future.getResult(timeout, TimeUnit.MILLISECONDS);
				return result;
			} catch(RuntimeException e) {
				throw e;
			} finally {
				//这个结果已经收到
				futrues.remove(String.valueOf(response.getSno()));
			}
		} else {
			return null;
		}
	}
	
	public Object Send(PullResponse response,boolean async) {
		if (channel == null)
			channel = getChannel(inetAddr.toString());
		if (channel != null) {	
			final InvokeFuture<Object> future=new InvokeFuture<Object>();
			futrues.put(String.valueOf(response.getSno()), future);
			ChannelFuture cfuture=channel.writeAndFlush(response);
			cfuture.addListener(new ChannelFutureListener() {
				@Override
				public void operationComplete(ChannelFuture rfuture) throws Exception {
					if(!rfuture.isSuccess()){
						future.setCause(rfuture.cause());
					}
				}
			});
			try {
				if (async) {//异步执行的话直接返回
					return null;
				}
				Object result=future.getResult(timeout, TimeUnit.MILLISECONDS);
				return result;
			} catch(RuntimeException e) {
				throw e;
			} finally {
				//这个结果已经收到
				if(!async)
					futrues.remove(String.valueOf(response.getSno()));
			}
		} else {
			return null;
		}
	}
	
	/**
	 * 发送无返回值
	 * @param response
	 */
	public void sendNoReturn(PullResponse response) {
		if (channel == null)
			channel = getChannel(inetAddr.toString());
		if (channel != null) {	
			channel.writeAndFlush(response);
		}
	}
	
	public void close() {
		if(channel!=null)
			try {
				channel.close().sync();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
	}

	public boolean isConnected() {
		return connected;
	}

	public boolean isClosed() {
		return (null == channel) || !channel.isOpen()
				|| !channel.isWritable() || !channel.isActive();
	}

	public boolean containsFuture(String key) {
		if(key==null)
			return false;
		return futrues.containsKey(key);
	}

	public InvokeFuture<Object> removeFuture(String key) {
		if(containsFuture(key))
			return futrues.remove(key);
		else
			return null;
	}
	
	public void setTimeOut(long timeout) {
		this.timeout=timeout;
	}
	
	public static void main(String[] args) {
		
		if(args == null || args.length < 6) {
			System.out.println("arguments number error!");
			System.exit(0);
		}
		String ip = "127.0.0.1";
		int port = 8088;
		int level = 2;
		for (int i = 0; i < args.length-1; i++) {
			if(args[i].equals("-i")) {
				ip = args[i+1];
			}
			if(args[i].equals("-p")) {
				port = Integer.valueOf(args[i+1]);
			}
			if(args[i].equals("-l")) {
				level = Integer.valueOf(args[i+1]);
			}
		}
		LoggerTool.LEVEL = level;
		ServerControl control = new ServerControl(ip, port);
		control.connect();
		
	}

}
