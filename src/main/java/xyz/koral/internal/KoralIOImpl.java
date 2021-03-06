package xyz.koral.internal;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import org.w3c.dom.Element;

import xyz.koral.Array;
import xyz.koral.Entry;
import xyz.koral.Koral;
import xyz.koral.KoralIO;
import xyz.koral.QID;

public class KoralIOImpl implements KoralIO
{
	public Koral load(boolean initSearchIndex, File ... files)
	{
		KoralImpl k = new KoralImpl();
		class Loader
		{
			XmlDocument add(File file)
			{
				File indexFile = new Index(file).getIndexFile();
				try 
				{
					XmlDocument xml = new XmlDocument(new FileInputStream(indexFile));
					FileSource source = new FileSource(file);
					List<LazyLoadSparseArray> arraysOfFile = add("", xml.root(), source);
					//System.out.println("initSearchIndex=" + initSearchIndex);
					if (initSearchIndex) 
					{
						k.pathToSearchIndex.put(source.getFile(), new SearchIndex(source.getFile(), arraysOfFile));
					}
					return xml;
				} 
				catch (IOException ex) 
				{
					throw new KoralError(ex);
				}
			}
			
			List<LazyLoadSparseArray> add(String namespace, Element elem, Source source)
			{
				String id = elem.getAttribute("id");
				List<LazyLoadSparseArray> list = new ArrayList<>();
				switch (elem.getTagName())
				{
				case "array": 
					LazyLoadSparseArray a = parseDiskArray(source, namespace, elem);
					k.add(a);
					list.add(a);
					break;
				case "koral": 
					namespace += namespace.length() > 0 && id.length() > 0 ? "." : "";
					namespace += id;
					for (Element child : XmlDocument.children(elem, "array", "koral"))
					{
						list.addAll(add(namespace, child, source));
					}
					break;
				}
				return list;
			}
			
			LazyLoadSparseArray parseDiskArray(Source source, String namespace, Element arrayElem)
			{
				String maxPitch = arrayElem.getAttribute("maxPitch");
				String stride = arrayElem.getAttribute("stride");
				String[] strideNames = null;
				if (stride == null || stride.length() < 1)
				{
					strideNames = new String[] { "" };
				}
				else
				{
					strideNames = stride.split("" + ArrayStream.strideSep);
				}
				String typeInfo = arrayElem.getAttribute("type");
				ArrayInfo meta = new ArrayInfo(new QID(namespace, arrayElem.getAttribute("id")), 
						maxPitch == null || maxPitch.length() < 1 ? 1 : new Long(maxPitch), strideNames,
						typeInfo.equals("numeric") ? Double.class : String.class);
				long count = new Long(arrayElem.getAttribute("count"));
				long sourceOffset = new Long(arrayElem.getAttribute(XmlDocument.sourceOffsetAtt));
				long noOfBytes = new Long(arrayElem.getAttribute(XmlDocument.noOfBytesAtt));
				
				KeyIndices keyIndices = new KeyIndices(arrayElem);
				keyIndices.indices.add(count);
				keyIndices.byteOffsets.add(noOfBytes);
				for (int i=0; i<keyIndices.byteOffsets.size(); i++)
				{
					keyIndices.byteOffsets.addTo(i, sourceOffset);
				}
				
				Long cacheSize = k.qidToCacheSize.get(meta.qid.get());
				LRUQueue queue = new LRUQueue(cacheSize != null ? cacheSize : k.defaultArrayCacheSize);
				LazyLoadSparseArray a = new LazyLoadSparseArray(meta, typeInfo, source, keyIndices.indices, keyIndices.byteOffsets, queue);
				return a;
			}
		}
		Loader l = new Loader();
		
		for (File f : files) l.add(f);
		
		return k;
	}
	
	
	public <T> void save(Iterable<T> objects, Class<T> clazz, QID baseID, OutputStream os)
	{
		class ArrayImpl implements Array
		{
			int strideSize = 1;
			FieldGetter getter;
			QID qid;
			
			CyclicBarrier barrier;
			
			long actualSize = 0;
			long actualMaxPitchSize = 0;
			
			Path tempDir;
			File getFile()
			{
				return new File(tempDir.toString(), qid.getID() + ".xml");
			}
			
			boolean noMoreObjects = false;
			Queue<Entry> buffer = new LinkedList<>();
			
			public ArrayImpl(FieldGetter getter)
			{
				this.getter = getter;
			}
			
			public Iterator<Entry> iterator() 
			{
				return new Iterator<Entry>()
				{
					public boolean hasNext() 
					{
						while (buffer.isEmpty())
						{
							if (noMoreObjects)
							{
								return false;
							}
							try 
							{
								barrier.await();
							} 
							catch (InterruptedException | BrokenBarrierException ex) 
							{
								throw new KoralError(ex);
							}
						}
						
						return true;
					}

					public Entry next() 
					{
						Entry result = buffer.poll();
						if (result != null)
						{
							actualSize = Math.max(actualSize, result.index() + 1);
							actualMaxPitchSize = Math.max(actualMaxPitchSize, result.pitchIndex() + 1);
						}
						return result;
					}
				};
			}

			public QID qid() 
			{
				return qid;
			}

			public long size() 
			{
				return Long.MAX_VALUE;
			}

			public long pitchSize(long index) 
			{
				throw new KoralError("Unsupported method");
			}

			public long maxPitchSize() 
			{
				return Long.MAX_VALUE;
			}

			public int strideSize() 
			{
				return strideSize;
			}

			public String strideName(int index) 
			{
				return "";
			}

			public boolean hasEntry(long index, long pitch) 
			{
				throw new KoralError("Unsupported method");
			}

			public Entry get(long index, long pitch) 
			{
				throw new KoralError("Unsupported method");
			}

			public List<Entry> getPitch(long index) 
			{
				throw new KoralError("Unsupported method");
			}

			public Class<?> type() 
			{
				return getter.type();
			}

			public String typeLiteral() 
			{
				return type() == String.class ? "string" : "numeric";
			}
		}
		List<ArrayImpl> arrays = new ArrayList<>();
		
		class FieldAnalzyer
		{
			@SuppressWarnings("unchecked")
			List<FieldGetter> getArray(Field field)
			{
				String name = field.getName();
				field.setAccessible(true);
				List<FieldGetter> getters = new ArrayList<>();
				if (Modifier.isTransient(field.getModifiers())) return getters;
				
				switch (field.getGenericType().getTypeName())
				{
				case "int": getters.add(new FieldGetter(Integer.class, name)
						{
							List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
							{
								DenseIntegerVector data = new DenseIntegerVector(1);
								data.add(field.getInt(o));
								List<Entry> entries = new ArrayList<>();
								entries.add(new VectorEntry(index, 0, 1, 0, data));
								return entries;
							}
						}); break;
				case "long": getters.add(new FieldGetter(Long.class, name)
						{
							List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
							{
								DenseLongVector data = new DenseLongVector(1);
								data.add(field.getLong(o));
								List<Entry> entries = new ArrayList<>();
								entries.add(new VectorEntry(index, 0, 1, 0, data));
								return entries;
							}
						}); break;
				case "double": getters.add(new FieldGetter(Double.class, name)
						{
							List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
							{
								DenseDoubleVector data = new DenseDoubleVector(1);
								data.add(field.getDouble(o));
								List<Entry> entries = new ArrayList<>();
								entries.add(new VectorEntry(index, 0, 1, 0, data));
								return entries;
							}
						}); break;
				case "float":  getters.add(new FieldGetter(Double.class, name)
						{
							List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
							{
								DenseDoubleVector data = new DenseDoubleVector(1);
								data.add(field.getFloat(o));
								List<Entry> entries = new ArrayList<>();
								entries.add(new VectorEntry(index, 0, 1, 0, data));
								return entries;
							}
						}); break;
				case "java.lang.String": getters.add(new FieldGetter(String.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						DenseStringVector data = new DenseStringVector(1);
						String value = (String) field.get(o);
						if (value == null) return new ArrayList<>();
						data.add(value);
						List<Entry> entries = new ArrayList<>();
						entries.add(new VectorEntry(index, 0, 1, 0, data));
						return entries;
					}
				}); break; 
				case "java.util.List<java.lang.String>": getters.add(new FieldGetter(String.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						List<String> values = (List<String>) field.get(o);
						if (values == null) return new ArrayList<>();
						DenseStringVector data = new DenseStringVector(values.size());
						for (String v : values) data.add(v);
						List<Entry> entries = new ArrayList<>(values.size());
						for (int i=0; i<values.size(); i++)
						{
							entries.add(new VectorEntry(index, i, 1, i, data));
						}
						return entries;
					}
				}); break; 
				case "java.util.List<java.lang.Double>": getters.add(new FieldGetter(Double.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						List<Double> values = (List<Double>) field.get(o);
						if (values == null) return new ArrayList<>();
						DenseDoubleVector data = new DenseDoubleVector(values.size());
						for (double v : values) data.add(v);
						List<Entry> entries = new ArrayList<>(values.size());
						for (int i=0; i<values.size(); i++)
						{
							entries.add(new VectorEntry(index, i, 1, i, data));
						}
						return entries;
					}
				}); break; 
				case "double[]": getters.add(new FieldGetter(Double.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						double[] values = (double[]) field.get(o);
						if (values == null) return new ArrayList<Entry>();
						DenseDoubleVector data = new DenseDoubleVector(values.length);
						for (double v : values) data.add(v);
						List<Entry> entries = new ArrayList<>(values.length);
						for (int i=0; i<values.length; i++)
						{
							entries.add(new VectorEntry(index, i, 1, i, data));
						}
						return entries;
					}
				}); break;
				case "long[]": getters.add(new FieldGetter(Double.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						long[] values = (long[]) field.get(o);
						if (values == null) return new ArrayList<Entry>();
						DenseLongVector data = new DenseLongVector(values.length);
						for (long v : values) data.add(v);
						List<Entry> entries = new ArrayList<>(values.length);
						for (int i=0; i<values.length; i++)
						{
							entries.add(new VectorEntry(index, i, 1, i, data));
						}
						return entries;
					}
				}); break;
				case "java.lang.String[]": getters.add(new FieldGetter(String.class, name)
				{
					List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
					{
						String[] values = (String[]) field.get(o);
						if (values == null) return new ArrayList<>();
						DenseStringVector data = new DenseStringVector(values.length);
						for (String v : values) data.add(v);
						List<Entry> entries = new ArrayList<>(values.length);
						for (int i=0; i<values.length; i++)
						{
							entries.add(new VectorEntry(index, i, 1, i, data));
						}
						return entries;
					}
				}); break;
				default: 
					List<FieldGetter> childGetters = new ArrayList<>();
					for (Field f : field.getType().getFields())
					{
						for (FieldGetter fg : getArray(f))
						{
							childGetters.add(new FieldGetterParent(field, name, fg));
						}
					}
					if (childGetters.isEmpty()) System.out.println("Not supported field type: " + field.getGenericType().getTypeName() + " for " + field.getName());
					getters.addAll(childGetters);
				}
				
				return getters;
			}
		}
		FieldAnalzyer fieldAnalzyer = new FieldAnalzyer();
		
		for (Field field : clazz.getFields()) //.getDeclaredFields())
		{
			if (field.getName().equals("index") && field.getGenericType().getTypeName().equals("long")) continue; // ignore index field
			List<FieldGetter> fieldGetters = fieldAnalzyer.getArray(field);
			
			for (FieldGetter fg : fieldGetters)
			{
				ArrayImpl array = new ArrayImpl(fg);
				array.qid = new QID(baseID, fg.name());
				arrays.add(array);
			}
		}
		
		class DataIter implements Runnable
		{
			final int bufferSize = 2000;
			
			long time = System.currentTimeMillis();
			long index = 0;
			boolean noMoreObjects = false;
			Iterator<T> iter = objects.iterator();
			
			public void run()
			{
				if (noMoreObjects) return;
				
				for (int i=0; i<bufferSize; i++)
				{
					if (!iter.hasNext())
					{
						noMoreObjects = true;
						for (ArrayImpl a : arrays)
						{
							a.noMoreObjects = true;
						}
						//System.out.println("NoMoreObjects index=" + index);
						break;
					}
					
					Object o = iter.next();
					for (ArrayImpl a : arrays)
					{
						try
						{
							List<Entry> entries = a.getter.get(o, index);
							if (entries != null) 
							{
								for (Entry e : entries)
								{
									if (e != null) a.buffer.add(e);
								}
							}
						}
						catch (IllegalArgumentException | IllegalAccessException ex)
						{
							throw new KoralError(ex);
						}
					}
					index++;
					if (index % 1000000 == 0) System.out.println(index + " " + (System.currentTimeMillis() - time) + " ms.");
				}
			}
		}
		DataIter iter = new DataIter();
		iter.run();
		
		CyclicBarrier barrier = new CyclicBarrier(arrays.size() + 1, iter);
	
		try
		{
			for (ArrayImpl a : arrays)
			{
				a.barrier = barrier;
				
				try
				{
					a.tempDir = Files.createTempDirectory("koral");
					System.out.println("Created " + a.tempDir.toString());
					Koral k = new KoralImpl(a);
					new Thread(() ->
					{
						try 
						{
							save(k, new FileOutputStream(a.getFile()));
							System.out.println(a.getFile() + " saved.");
							
							// signal end
							try 
							{
								a.barrier.await();
							} 
							catch (InterruptedException | BrokenBarrierException ex) 
							{
								throw new KoralError(ex);
							}
						} 
						catch (FileNotFoundException ex) 
						{
							throw new KoralError(ex);
						}
					}).start();
				}
				catch (IOException ex)
				{
					throw new KoralError(ex);
				}
			}
			
			while (!iter.noMoreObjects)
			{
				//System.out.println("Main thread wait for barrier");
				try 
				{
					barrier.await();
				} 
				catch (InterruptedException | BrokenBarrierException ex) 
				{
					throw new KoralError(ex);
				}
			}
			
			//System.out.println("Main thread wait for end.");
			// wait for end
			try 
			{
				barrier.await();
			} 
			catch (InterruptedException | BrokenBarrierException ex) 
			{
				throw new KoralError(ex);
			}
			
			// merge all temp arrays
			System.out.println("Now merging...");
			Koral result = new KoralImpl();
			for (ArrayImpl a : arrays)
			{
				// debug
//				try
//				{
//					StringBuilder sb = new StringBuilder();
//					BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(a.getFile()), "UTF-8"));
//					String line = null;
//					while ((line = reader.readLine()) != null)
//					{
//						sb.append(line);
//						sb.append("\n");
//					}
//					reader.close();
//					System.out.println("---");
//					System.out.println(sb.toString());
//					System.out.println("---");
//				}
//				catch (Exception ex)
//				{
//					
//				}
				// debug
				
				KoralImpl kk = (KoralImpl) load(false, a.getFile());
				for (Array a_ : kk.arrays)
				{
					result.add(new Array() 
					{
						public Iterator<Entry> iterator() { return a_.iterator(); }
						public String typeLiteral() { return a_.typeLiteral(); }
						public Class<?> type() { return a_.type(); }
						public int strideSize() { return a_.strideSize(); }
						public String strideName(int index) { return a.strideName(index); }
						public long size() { return a.actualSize; }
						public QID qid() { return a.qid(); }
						public long pitchSize(long index) { return a_.pitchSize(index); }
						public long maxPitchSize() { return a.actualMaxPitchSize; }
						public boolean hasEntry(long index, long pitch) { return a_.hasEntry(index, pitch); }
						public List<Entry> getPitch(long index) { return a_.getPitch(index); }
						public Entry get(long index, long pitch) { return a_.get(index, pitch); }
					});
				}
			}
			System.out.println("Merged Koral: " + result.noOfArrays());
			
			save(result, os);
			System.out.println("done saving koral.");
		}
		finally
		{
			// clean temp dirs
			for (ArrayImpl a : arrays)
			{
				if (a.tempDir == null || !Files.exists(a.tempDir)) continue;
		    	try 
		    	{
					Files.walkFileTree(a.tempDir, new SimpleFileVisitor<Path>() 
					{
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException 
						{
							Files.delete(file);
							//System.out.println("Deleted " + file.toString());
							return FileVisitResult.CONTINUE;
						}

						public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException 
						{
							Files.delete(dir);
							//System.out.println("Deleted " + dir.toString());
							return FileVisitResult.CONTINUE;
						}
					});
				} 
		    	catch (IOException ex) 
		    	{
					throw new KoralError(ex);
				}
			}
		}
	}
	
	static String decomma(String text)
	{
		return text.contains(",") ? "\"" + text + "\"" : text;
	}
	
	public void saveAsCsv(Koral koral, OutputStream os)
	{
		List<Array> arrays = koral.arrays();
		try
		{
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
			for (int i=0; i<arrays.size(); i++)
			{
				Array a = arrays.get(i);
				writer.write(decomma(a.qid().getID()));
				if (i < arrays.size() - 1) writer.write(",");
			}
			writer.write("\n");
			
			koral.asTable(arrays).forEach(list -> 
			{
				try 
				{
					for (int j=0; j<list.size(); j++)
					{
						List<Entry> col = list.get(j);
						if (col != null)
						{
							StringBuilder sb = new StringBuilder();
							for (int i=0; i<col.size(); i++)
							{
								sb.append(col.get(i).getS());
								if (i < col.size() - 1) sb.append("|");
							}
							writer.write(decomma(sb.toString()));
						}
						if (j < list.size() - 1) writer.write(",");
					}
					writer.write("\n");
				} 
				catch (IOException ex) 
				{
					throw new KoralError(ex);
				}
			});
			
			writer.close();
		}
		catch (IOException ex)
		{
			throw new KoralError(ex);
		}
	}
}

abstract class FieldGetter
{
	Class<?> type;
	String name;
	
	public FieldGetter(Class<?> type, String name)
	{
		this.type = type;
		this.name = name;
	}
	
	abstract List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException;
	
	Class<?> type()
	{
		return type;
	}
	
	String name()
	{
		return name;
	}
}

class FieldGetterParent extends FieldGetter
{
	FieldGetter child;
	Field field;
	
	public FieldGetterParent(Field field, String name, FieldGetter child)
	{
		super(child.type(), name + "." + child.name);
		this.child = child;
		this.field = field;
	}

	List<Entry> get(Object o, long index) throws IllegalArgumentException, IllegalAccessException 
	{
		return child.get(field.get(o), index);
	}
}
