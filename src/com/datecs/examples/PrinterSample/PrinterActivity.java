package com.datecs.examples.PrinterSample;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.UUID;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.datecs.api.printer.Printer;
import com.datecs.api.printer.PrinterInformation;
import com.datecs.api.printer.ProtocolAdapter;
import com.datecs.examples.PrinterSample.network.PrinterServer;
import com.datecs.examples.PrinterSample.network.PrinterServerListener;

public class PrinterActivity extends Activity {
    // Debug
    private static final String LOG_TAG = "PrinterSample";
    private static final boolean DEBUG = true;
    
    // Request to get the bluetooth device
    private static final int REQUEST_GET_DEVICE = 0; 
    
    // Request to get the bluetooth device
    private static final int DEFAULT_NETWORK_PORT = 9100; 

    // A handler to display notifications
	private final Handler mHandler = new Handler();
	
	
    // Member variables
	private Printer mPrinter;
	private PrinterInformation mPrinterInfo;
	private BluetoothSocket mBluetoothSocket;
	private PrinterServer mPrinterServer;
	private Socket mPrinterSocket;
	private boolean mRestart;
	    	
	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.printer);
        
        mRestart = true;
        waitForConnection();
    }

    @Override
	protected void onDestroy() {
        super.onDestroy();
        mRestart = false;               
        close();
        finish();
	}	
		    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_GET_DEVICE) {
            if (resultCode == DeviceListActivity.RESULT_OK) {   
            	String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
            	//address = "192.168.11.136:9100";
            	if (BluetoothAdapter.checkBluetoothAddress(address)) {
            		establishBluetoothConnection(address);
            	} else {
            		establishNetworkConnection(address);
            	}
            } else if (resultCode == RESULT_CANCELED) {
                
            } else {
                finish();
            }
        }
    }
        
//    private void toast(final String text) {
//        mHandler.post(new Runnable() {            
//            @Override
//            public void run() {
//                Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
//            }
//        });
//    }
    
//    private void dialog(final int iconResId, final String title, final String msg) {
//        mHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                AlertDialog.Builder builder = new AlertDialog.Builder(PrinterActivity.this);
//                builder.setIcon(iconResId);
//                builder.setTitle(title);
//                builder.setMessage(msg);
//                
//                AlertDialog dlg = builder.create();                
//                dlg.show();             
//            }           
//        });             
//    }
    
    private void error(final String text, boolean restart) {        
        mHandler.post(new Runnable() {
            @Override
            public void run() {             
//                Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();                
            }           
        });
        
        if (restart) {
            waitForConnection();
        }
    }
    
    private void doJob(final Runnable job, final int resId) {
        // Start the job from main thread
        mHandler.post(new Runnable() {            
            @Override
            public void run() {
             // Progress dialog title
                String title = getString(R.string.title_please_wait);
                // Progress dialog message
                String text = getString(resId);
                // Progress dialog available due job execution
                final ProgressDialog dialog = ProgressDialog.show(PrinterActivity.this, title, text);
                new Thread(new Runnable() {            
                    @Override
                    public void run() {                
                        try {
                            job.run();
                        } finally {
                            dialog.dismiss();
                        }
                    }
                }).start();   
            }
        }); 
    }
    
    protected void initPrinter(InputStream inputStream, OutputStream outputStream) throws IOException {
        //InputStream in = inputStream;
        InputStream in = new PatchedInputStream(inputStream);
        OutputStream out = outputStream;        
        ProtocolAdapter protocolAdapter = new ProtocolAdapter(in, out);
        
        if (protocolAdapter.isProtocolEnabled()) {
            final ProtocolAdapter.Channel channel = protocolAdapter.getChannel(ProtocolAdapter.CHANNEL_PRINTER);
//            channel.setListener(mChannelListener);
            // Create new event pulling thread
            new Thread(new Runnable() {                
                @Override
                public void run() {
                    while (true) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        
                        try {
                            channel.pullEvent();
                        } catch (IOException e) {
                            error(e.getMessage(), mRestart);
                            break;
                        }
                    }
                }
            }).start();
            mPrinter = new Printer(channel.getInputStream(), channel.getOutputStream());
        } else {
            mPrinter = new Printer(in, out);
        }
        
        mPrinterInfo = mPrinter.getInformation();
        
//        mHandler.post(new Runnable() {          
//            @Override
//            public void run() {
//                ((ImageView)findViewById(R.id.icon)).setImageResource(R.drawable.icon);
//                ((TextView)findViewById(R.id.name)).setText(mPrinterInfo.getName());
//            }
//        });
    }
    
    public synchronized void waitForConnection() {
        close();
        
        // Show dialog to select a Bluetooth device. 
        startActivityForResult(new Intent(this, DeviceListActivity.class), REQUEST_GET_DEVICE);
        
        // Start server to listen for network connection.
        try {
            mPrinterServer = new PrinterServer(new PrinterServerListener() {                
                @Override
                public void onConnect(Socket socket) {
                    if (DEBUG) Log.d(LOG_TAG, "Accept connection from " + socket.getRemoteSocketAddress().toString());
                    
                    // Close Bluetooth selection dialog
                    finishActivity(REQUEST_GET_DEVICE);                    
                    
                    mPrinterSocket = socket;
                    try {
                        InputStream in = socket.getInputStream();
                        OutputStream out = socket.getOutputStream();
                        initPrinter(in, out);
                    } catch (IOException e) {                        
                        error(getString(R.string.msg_failed_to_init) + ". " + e.getMessage(), mRestart);
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }   
    
    private void establishBluetoothConnection(final String address) {
    	closePrinterServer();
        
        doJob(new Runnable() {           
            @Override
            public void run() {      
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();                
                BluetoothDevice device = adapter.getRemoteDevice(address);                    
                UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                InputStream in = null;
                OutputStream out = null;
                
                adapter.cancelDiscovery();
                
                try {
                    if (DEBUG) Log.d(LOG_TAG, "Connect to " + device.getName());
                    mBluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                    mBluetoothSocket.connect();
                    in = mBluetoothSocket.getInputStream();
                    out = mBluetoothSocket.getOutputStream();                                        
                } catch (IOException e) {                    
                    error(getString(R.string.msg_failed_to_connect) + ". " +  e.getMessage(), mRestart);
                    return;
                }                                  
                
                try {
                    initPrinter(in, out);
                    
                    //Inserido para Imprimir
//TODO                    
                    printTextFile();
                } catch (IOException e) {
                    error(getString(R.string.msg_failed_to_init) + ". " +  e.getMessage(), mRestart);
                    return;
                }
            }
        }, R.string.msg_connecting); 
    }
    
    private void establishNetworkConnection(final String address) {
    	closePrinterServer();
        
        doJob(new Runnable() {           
            @Override
            public void run() {            	
            	Socket s = null;
            	try {
            		String[] url = address.split(":");
            		int port = DEFAULT_NETWORK_PORT;
            		
            		try {
            			if (url.length > 1)  {
            				port = Integer.parseInt(url[1]);
            			}
            		} catch (NumberFormatException e) { }
            		
            		s = new Socket(url[0], port);
            		s.setKeepAlive(true);
                    s.setTcpNoDelay(true);
	            } catch (UnknownHostException e) {
	            	error(getString(R.string.msg_failed_to_connect) + ". " +  e.getMessage(), mRestart);
                    return;
	            } catch (IOException e) {
	            	error(getString(R.string.msg_failed_to_connect) + ". " +  e.getMessage(), mRestart);
                    return;
	            }            
            	
                InputStream in = null;
                OutputStream out = null;
                
                try {
                    if (DEBUG) Log.d(LOG_TAG, "Connect to " + address);
                    mPrinterSocket = s;                    
                    in = mPrinterSocket.getInputStream();
                    out = mPrinterSocket.getOutputStream();                                        
                } catch (IOException e) {                    
                    error(getString(R.string.msg_failed_to_connect) + ". " +  e.getMessage(), mRestart);
                    return;
                }                                  
                
                try {
                    initPrinter(in, out);
                } catch (IOException e) {
                    error(getString(R.string.msg_failed_to_init) + ". " +  e.getMessage(), mRestart);
                    return;
                }
            }
        }, R.string.msg_connecting); 
    }
    
    private synchronized void closeBlutoothConnection() {        
        // Close Bluetooth connection 
        BluetoothSocket s = mBluetoothSocket;
        mBluetoothSocket = null;
        if (s != null) {
            if (DEBUG) Log.d(LOG_TAG, "Close Blutooth socket");
            try {
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }        
    }
    
    private synchronized void closeNetworkConnection() {
        // Close network connection
        Socket s = mPrinterSocket;
        mPrinterSocket = null;
        if (s != null) {
            if (DEBUG) Log.d(LOG_TAG, "Close Network socket");
            try {
                s.shutdownInput();
                s.shutdownOutput();
                s.close();
            } catch (IOException e) {
                e.printStackTrace();
            }            
        }
    }
    
    private synchronized void closePrinterServer() {
    	closeNetworkConnection();
    	
        // Close network server
        PrinterServer ps = mPrinterServer;
        mPrinterServer = null;
        if (ps != null) {
            if (DEBUG) Log.d(LOG_TAG, "Close Network server");
            try {
                ps.close();
            } catch (IOException e) {                
                e.printStackTrace();
            }            
        }     
    }
    
    private synchronized void close() {        
        closeBlutoothConnection();
        closeNetworkConnection();  
        closePrinterServer();
    }

    //Metodo de Impressão do Arquivo
    private void printTextFile() {
	    doJob(new Runnable() {           
            @Override
            public void run() {
            	try {   
            	    if (DEBUG) Log.d(LOG_TAG, "Print Text");
            	    
            	    StringBuffer sb = new StringBuffer();
            		String lstrlinha;
            		String lstrNomeArq = "arqimpressao.txt";
            		File arq = new File(Environment.getExternalStorageDirectory() + "/external_sd/siv-ngmobile/", lstrNomeArq);
        			BufferedReader br = new BufferedReader(new FileReader(arq));

        			while ((lstrlinha = br.readLine()) != null) {
        				sb.append("{reset}"+lstrlinha.toString()+"{br}");
        			}

        			mPrinter.reset();  
            		mPrinter.printTaggedText(sb.toString(), "ISO-8859-1");   		
            		mPrinter.feedPaper(50);
            		
            		gravarSatus("sucesso");

            		onDestroy();
            	} catch (IOException e) {
            		gravarSatus("erro");
            	    error(getString(R.string.msg_failed_to_print_text) + ". " + e.getMessage(), mRestart);    		
				}
            }
	    }, R.string.msg_printing_text);
	    
	}
    
    /**
     * Grava o status da Impressão
     */
    public void gravarSatus(String msgStatus) {
		
		try {
			String nomeArquivo = "status.txt";
			File arq = new File(Environment.getExternalStorageDirectory() + "/external_sd/siv-ngmobile/txt/", nomeArquivo);
			byte[] dados = msgStatus.getBytes();
			FileOutputStream fos = new FileOutputStream(arq);
			fos.write(dados);
			fos.flush();
			fos.close();
		} catch (Exception e) {
			error("Erro : " + e.getMessage(), mRestart);
		}
	}
    

	private void printText() {
	    doJob(new Runnable() {           
            @Override
            public void run() {
            	            	
            	/*
	            	{reset}   - Redefinir as configurações padrão.
	            	{br}      - Quebra de linha. Equivalente de nova linha.	
	            	{b}, {/b} -	Definir ou limpar estilo negrito.
	            	{u}, {/u} -	Definir ou limpar sublinhar estilo de fonte.
	            	{i}, {/i} -	Definir ou limpar estilo itálico.
	            	{s}, {/s} - Definir ou limpar estilo de fonte pequena.	
	            	{h}, {/h} -	Definir ou limpar estilo de fonte alta.
	            	{w}, {/w} -	Definir ou limpar estilo da fonte de largura.
	            	{left}	  - Alinha o texto à margem esquerda do papel.
	            	{center}  -	Alinha texto ao centro de papel.
	            	{right}	  - Alinha o texto à borda do papel certo.
	            	
            	 */

        		StringBuffer sb = new StringBuffer();
        		sb.append("{reset}{center}{w}{h}DAV PEDIDO{br}");
                sb.append("{reset}{center}NÃO É DOCUMENTO FISCAL{br}");
                sb.append("{reset}{center}{s}----------------------------------------------------------------{/s}{br}");
                sb.append("{reset}Nome da Empresa Vendedora{br}");
                sb.append("{reset}CNPJ: 11.111.111/0001-00{br}");
                sb.append("{reset}Vendedor: 000-NOME DO FUNCIONARIO{br}");
                sb.append("{reset}Nº Documento: 0113/000000765{br}");
                sb.append("{reset}Data: 26/03/13 15:07{br}");
                sb.append("{reset}Cliente: ANTONIO ALBERTO CAMPOS{br}");
                sb.append("{reset}Telefone: (75)36222545{br}");
                sb.append("{reset}{center}{s}----------------------------------------------------------------{/s}{br}");
                sb.append("{reset}{left}{s}ITEM  CÓDIGO  DESCRIÇÃO  QTD. UND. VL UNIT(R$)  VL TOTAL(R$){/s}{br}");
                sb.append("{reset}{center}{s}----------------------------------------------------------------{/s}{br}");
                
                sb.append("{reset}{left}1.  5019  Notebook Asus VX051H {br}");
                sb.append("{reset}{right}2  UND. X 2.000,00  R$ 4.000,00{br}");
                
                sb.append("{reset}{left}2.  5019  Notebook Philco com Intel Dual Core {br}");
                sb.append("{reset}{right}3  UND. X 500,00  R$ 1.500,00{br}");
                
                sb.append("{reset}{left}3.  5019  Notebook SIM Positivo 1060 com AMD Dual Core 4GB {br}");
                sb.append("{reset}{right}5  UND. X 10,00  R$ 50,00{br}");
                
                sb.append("{reset}{center}{s}----------------------------------------------------------------{/s}{br}");
                sb.append("{reset}{center}NÃO É DOCUMENTO FISCAL{br}");
                sb.append("{reset}{left}VALOR DO PEDIDO:{/left}                   "); 
                sb.append("{reset}{right}{b}R$ 5.550,00{/b}{/right}{br}");
                sb.append("{reset}{left}30 Dias:{/left}                   "); 
                sb.append("{reset}{right}{b}R$ 5.000,00{/b}{/right}{br}");
                sb.append("{reset}{center}{s}----------------------------------------------------------------{/s}{br}");
                
                sb.append("{reset}{center}Espaço para Observações do Pedido.{br}");
                sb.append("{br}");
                                    
            	try {   
            	    if (DEBUG) Log.d(LOG_TAG, "Print Text");
            		mPrinter.reset();  
            		mPrinter.printTaggedText(sb.toString(), "ISO-8859-1");    		
            		mPrinter.feedPaper(110);            		            		
            	} catch (IOException e) {
            	    error(getString(R.string.msg_failed_to_print_text) + ". " + e.getMessage(), mRestart);    		
            	}
            }
	    }, R.string.msg_printing_text);
	}
	
//  private void printSelfTest() {        
//  doJob(new Runnable() {           
//      @Override
//      public void run() {
//          try {           
//              if (DEBUG) Log.d(LOG_TAG, "Print Self Test");
//              mPrinter.printSelfTest();                       
//          } catch (IOException e) {
//              error(getString(R.string.msg_failed_to_print_self_test) + ". " + e.getMessage(), mRestart);
//          }
//      }
//  }, R.string.msg_printing_self_test);		
//}
//
//	
//	private void printImage() {
//	    doJob(new Runnable() {           
//            @Override
//            public void run() {
//                BitmapFactory.Options options = new BitmapFactory.Options();
//                options.inScaled = false;                
//        		Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.sample);        		
//        		final int width = bitmap.getWidth();
//        		final int height = bitmap.getHeight();
//        		final int[] argb = new int[width * height];		
//        		bitmap.getPixels(argb, 0, width, 0, 0, width, height);	
//        		bitmap.recycle();
//        				
//        		try {
//        		    if (DEBUG) Log.d(LOG_TAG, "Print Image");
//        		    mPrinter.reset();		     
//            		mPrinter.printImage(argb, width, height, Printer.ALIGN_CENTER, true);
//            		mPrinter.feedPaper(110);		    
//                } catch (IOException e) {
//                    error(getString(R.string.msg_failed_to_print_image) + ". " + e.getMessage(), mRestart);
//                }
//            }
//	    }, R.string.msg_printing_image);
//	}
//	
//	private void printPage() {
//	    doJob(new Runnable() {           
//            @Override
//            public void run() {
//        	    if (mPrinterInfo == null || !mPrinterInfo.isPageModeSupported()) {
//        	        dialog(R.drawable.page, 
//        	                getString(R.string.title_warning), 
//        	                getString(R.string.msg_unsupport_page_mode));
//        	        return;
//        	    }
//        	            
//                try {
//                    if (DEBUG) Log.d(LOG_TAG, "Print Page");
//                    mPrinter.reset();            
//                    mPrinter.selectPageMode();   
//                    
//                    mPrinter.setPageRegion(0, 0, 160, 320, Printer.PAGE_LEFT);            
//                    mPrinter.setPageXY(0, 4);            
//                    mPrinter.printTaggedText("{reset}{center}{b}PARAGRAPH I{br}");
//                    mPrinter.drawPageRectangle(0, 0, 160, 32, Printer.FILL_INVERTED);            
//                    mPrinter.setPageXY(0, 34);
//                    mPrinter.printTaggedText("{reset}Text printed from left to right" + ", feed to bottom. Starting point in left top corner of the page.{br}");
//                    mPrinter.drawPageFrame(0, 0, 160, 320, Printer.FILL_BLACK, 1);
//                    
//                    mPrinter.setPageRegion(160, 0, 160, 320, Printer.PAGE_TOP);            
//                    mPrinter.setPageXY(0, 4);            
//                    mPrinter.printTaggedText("{reset}{center}{b}PARAGRAPH II{br}");
//                    mPrinter.drawPageRectangle(160 - 32, 0, 32, 320, Printer.FILL_INVERTED);            
//                    mPrinter.setPageXY(0, 34);
//                    mPrinter.printTaggedText("{reset}Text printed from top to bottom" + ", feed to left. Starting point in right top corner of the page.{br}");
//                    mPrinter.drawPageFrame(0, 0, 160, 320, Printer.FILL_BLACK, 1);
//                    
//                    mPrinter.setPageRegion(160, 320, 160, 320, Printer.PAGE_RIGHT);            
//                    mPrinter.setPageXY(0, 4);            
//                    mPrinter.printTaggedText("{reset}{center}{b}PARAGRAPH III{br}");
//                    mPrinter.drawPageRectangle(0, 320 - 32, 160, 32, Printer.FILL_INVERTED);            
//                    mPrinter.setPageXY(0, 34);
//                    mPrinter.printTaggedText("{reset}Text printed from right to left" + ", feed to top. Starting point in right bottom corner of the page.{br}");
//                    mPrinter.drawPageFrame(0, 0, 160, 320, Printer.FILL_BLACK, 1);
//                    
//                    mPrinter.setPageRegion(0, 320, 160, 320, Printer.PAGE_BOTTOM);            
//                    mPrinter.setPageXY(0, 4);            
//                    mPrinter.printTaggedText("{reset}{center}{b}PARAGRAPH IV{br}");
//                    mPrinter.drawPageRectangle(0, 0, 32, 320, Printer.FILL_INVERTED);            
//                    mPrinter.setPageXY(0, 34);
//                    mPrinter.printTaggedText("{reset}Text printed from bottom to top" + ", feed to right. Starting point in left bottom corner of the page.{br}");
//                    mPrinter.drawPageFrame(0, 0, 160, 320, Printer.FILL_BLACK, 1);
//                    
//                    mPrinter.printPage();
//                    mPrinter.selectStandardMode();
//                    mPrinter.feedPaper(110);          
//                } catch (IOException e) {
//                    error(getString(R.string.msg_failed_to_print_page) + ". " + e.getMessage(), mRestart);            
//                }
//            }
//	    }, R.string.msg_printing_page);
//    }
//	
//	private void printBarcode() {
//	    doJob(new Runnable() {           
//            @Override
//            public void run() {
//        		try {    		
//        		    if (DEBUG) Log.d(LOG_TAG, "Print Barcode");
//        			mPrinter.reset();
//        						
//        			mPrinter.setBarcode(Printer.ALIGN_CENTER, false, 2, Printer.HRI_BELOW, 100);
//        			mPrinter.printBarcode(Printer.BARCODE_EAN13, "123456789012");
//        			mPrinter.feedPaper(38);
//        			
//        			mPrinter.setBarcode(Printer.ALIGN_CENTER, false, 2, Printer.HRI_BOTH, 100);
//        			mPrinter.printBarcode(Printer.BARCODE_CODE128, "ABCDEF123456");
//        			mPrinter.feedPaper(38);
//        						
//        			mPrinter.setBarcode(Printer.ALIGN_CENTER, false, 2, Printer.HRI_NONE, 100);
//        			mPrinter.printBarcode(Printer.BARCODE_PDF417, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
//        			mPrinter.feedPaper(38);
//        			
//        			mPrinter.setBarcode(Printer.ALIGN_CENTER, false, 2, Printer.HRI_NONE, 100);
//        			mPrinter.printQRCode(4, 3, "http://www.datecs.bg");        			
//        			mPrinter.feedPaper(38);
//        			
//        			mPrinter.feedPaper(110);
//            	} catch (IOException e) {
//            	    error(getString(R.string.msg_failed_to_print_barcode) + ". " + e.getMessage(), mRestart);    	    
//            	}
//            }
//	    }, R.string.msg_printing_barcode);
//	}
//
//	private void readMagstripe() {
//	    doJob(new Runnable() {           
//            @Override
//            public void run() {
//        		String[] tracks = null;
//        		FinancialCard card = null;		
//        		
//            	try {
//            	    if (DEBUG) Log.d(LOG_TAG, "Read Magstripe");
//            	    if (mPrinterInfo != null && mPrinterInfo.getName().startsWith("CMP-10")) {
//            	        // The printer CMP-10 can read only two tracks at once.
//            	        tracks = mPrinter.readCard(true, true, false, 15000);
//            	    } else {
//            	        tracks = mPrinter.readCard(true, true, true, 15000);
//            	    }
//            	} catch (IOException e) { 
//            		error(getString(R.string.msg_failed_to_read_card) + ". " + e.getMessage(), mRestart);
//            	}    	
//            	
//            	if (tracks != null) {
//            		StringBuffer msg = new StringBuffer();
//            		 
//        	    	if (tracks[0] == null && tracks[1] == null && tracks[2] == null) {
//        	    		msg.append(getString(R.string.no_card_read));
//        	    	} else {
//        	    		if (tracks[0] != null) {
//        	    			card = new FinancialCard(tracks[0]);
//        	    		} else if (tracks[1] != null) {
//        	    			card = new FinancialCard(tracks[1]);
//        	    		}
//        	    		
//        	    		if (card != null) {
//        	    			msg.append(getString(R.string.card_no) + ": " + card.getNumber());
//        	    			msg.append("\n");
//        	    			msg.append(getString(R.string.holder) + ": " + card.getName());
//        	    			msg.append("\n");
//        	    			msg.append(getString(R.string.exp_date) + ": " + String.format("%02d/%02d", 
//        	    					card.getExpiryMonth(), card.getExpiryYear()));
//        	    			msg.append("\n");	    			
//        	    		}
//        	    		
//        	    		if (tracks[0] != null) {
//        	    			msg.append("\n");
//        	    			msg.append(tracks[0]);
//        	    			
//        	    		}
//        	    		if (tracks[1] != null) {
//        	    			msg.append("\n");
//        	    			msg.append(tracks[1]);	    			
//        	    		}	    		
//        	    		if (tracks[2] != null) {
//        	    			msg.append("\n");
//        	    			msg.append(tracks[2]);
//        	    		}	    		
//        	    	}
//        	    	
//        	    	dialog(R.drawable.card,        	    	        
//        	    	        getString(R.string.card_info), 
//        	    	        msg.toString());
//            	}
//            }
//	    }, R.string.msg_reading_magstripe);
//	}	 
//	
//	private void readBarcode(final int timeout) {
//	    doJob(new Runnable() {           
//            @Override
//            public void run() {
//        		String barcode = null;
//        		
//            	try {
//            	    if (DEBUG) Log.d(LOG_TAG, "Read Barcode");
//            		barcode = mPrinter.readBarcode(timeout);
//            	} catch (IOException e) {     		
//            		error(getString(R.string.msg_failed_to_read_barcode) + ". " + e.getMessage(), mRestart);
//            	}    	
//            	
//            	if (barcode != null) {    		
//        	    	dialog(R.drawable.readbarcode, getString(R.string.barcode), barcode); 
//            	}
//            }
//	    }, R.string.msg_reading_barcode);
//	}       	
}

