package com.ibus.navigation.map.db;

import static com.ibus.map.utils.SimpleDBNames.DESC_ATTR;
import static com.ibus.map.utils.SimpleDBNames.LAT_ATTR;
import static com.ibus.map.utils.SimpleDBNames.LINES_ATTR;
import static com.ibus.map.utils.SimpleDBNames.LINE_NAME_ATTR;
import static com.ibus.map.utils.SimpleDBNames.LINE_SEGMENTS;
import static com.ibus.map.utils.SimpleDBNames.LON_ATTR;
import static com.ibus.map.utils.SimpleDBNames.SEGMENT_POINTS;
import static com.ibus.map.utils.SimpleDBNames.STATIONS_DETAILS;
import static com.ibus.map.utils.SimpleDBNames.SUBMAP_ATTR;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.simpledb.AmazonSimpleDB;
import com.amazonaws.services.simpledb.AmazonSimpleDBClient;
import com.amazonaws.services.simpledb.model.Attribute;
import com.amazonaws.services.simpledb.model.Item;
import com.amazonaws.services.simpledb.model.SelectRequest;
import com.amazonaws.services.simpledb.model.SelectResult;
import com.google.common.collect.TreeMultimap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.ibus.map.LineSegment;
import com.ibus.map.Node;
import com.ibus.map.Stop;
import com.ibus.map.TimedPoint;
public class SimpleDBMapLoader implements IMapDBLoader {
	private AmazonSimpleDB sdb;
	private Gson gson = new Gson();
	private ExecutorService exec = new ThreadPoolExecutor(10, 30, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

	@Inject
	public SimpleDBMapLoader(@Named("AWS USER KEY")String userKey, @Named("AWS SECRET KEY")String secretKey) {
		sdb = new AmazonSimpleDBClient(new BasicAWSCredentials(userKey,
				secretKey));
	}

	private List<Item> getAllItems(String query) {
		SelectRequest selectRequest = new SelectRequest(query);
		SelectResult res = sdb.select(selectRequest);
		List<Item> items = res.getItems();
		while(res.getNextToken()!=null && !res.getNextToken().isEmpty()){
			selectRequest.setNextToken(res.getNextToken());
			res = sdb.select(selectRequest);
			items.addAll(res.getItems());
		}
		return items;
	}

	private Stop toStop(List<Attribute> list, int index) {
		Stop stop = new Stop();
		for (Attribute attr : list) {
			if (attr.getName().equalsIgnoreCase(LON_ATTR)) {
				stop.setLongitude(Double.valueOf(attr.getValue()));
			} else if (attr.getName().equalsIgnoreCase(LAT_ATTR)) {
				stop.setLatitude(Double.valueOf(attr.getValue()));
			}else if (attr.getName().equalsIgnoreCase(DESC_ATTR)) {
				stop.setDesc(attr.getValue());
			}else if (attr.getName().equalsIgnoreCase(LINES_ATTR)) {
				Type collectionType = new TypeToken<LinkedList<String>>(){}.getType();
				List<String> lst = gson.fromJson(attr.getValue(), collectionType);
				Collections.sort(lst);
				for(String l:lst){
					Node n = new Node(l, index);
					index++;
					stop.addNode(n);
				}
			}
		}
		return stop;
	}

	
	/* (non-Javadoc)
	 * @see com.ibus.navigation.map.db.IMapDBLoader#getRoutesMap(java.lang.String)
	 * public static class NodesGraph{
		public Edge[] edges;
		public int nodesNumber;
	
		public static class Edge{
			public Node start;
			public Node end;
			public int weight;
			
			public Edge(Node start, Node end, int weight) {
				this.start = start;
				this.end = end;
				this.weight = weight;
			}
		}
	 */
	@Override
	public NodesGraph getRoutesMap(String submap) {
		LineSegment[] segments = getLineSegments(submap);
		//the nodes are ordered by: 1)stopid - lat_lon 2)natural order of lineid
		
		
		//go over segments, each segment represents an edge, we need to sort the nodes 
		//before assigning them ids each node will be represented by stopid for primary sort
		//and by lineid for secondary sort
		
		Comparator<Node> nodesComp = new Comparator<Node>(){
			@Override
			public int compare(Node o1, Node o2) {
				return o1.getLine().compareTo(o2.getLine());
			}
		};

		
		TreeMultimap<String, Node> nodes = TreeMultimap.create(String.CASE_INSENSITIVE_ORDER,nodesComp);  

		NodesGraph graph = new NodesGraph();
		graph.segments = segments;
		ArrayList<Edge> lst = new ArrayList<IMapDBLoader.Edge>();
		HashMap<String, Node> nodesByLineAndStation = new HashMap<String, Node>();
		for(LineSegment ls:segments){
			addSegment(nodes, lst, nodesByLineAndStation, ls);
		}
		graph.edges = lst.toArray(new Edge[0]);
		//receive a sorted nodes by stopids and by lineids as a secondary sort
		Set<Entry<String, Node>> values = nodes.entries(); 
		graph.nodesNumber = values.size(); 
		int i = 0;
		for(Entry<String, Node> n:values){
			//the key is the stop id
			n.getValue().setId(i);
			i++;
		}
		return graph;
	}

	private void addSegment(TreeMultimap<String, Node> nodes,
			ArrayList<Edge> lst, HashMap<String, Node> nodesByLineAndStation,
			LineSegment ls) {
		Node strt = nodesByLineAndStation.get(ls.getLineId()+ls.getStart().getId()); 
		Node end = nodesByLineAndStation.get(ls.getLineId()+ls.getEnd().getId());	
		if(strt == null){
			strt = new Node(ls.getLineId());
			nodesByLineAndStation.put(ls.getLineId()+ls.getStart().getId(), strt);
			nodes.put(ls.getStart().getId(), strt);
		}
		if(end == null){
			end = new Node(ls.getLineId());
			nodesByLineAndStation.put(ls.getLineId()+ls.getEnd().getId(), end);	
			nodes.put(ls.getEnd().getId(), end);
		}
		
		Edge edge = new Edge(strt, end, ls.getDuration());
		lst.add(edge);
	}

	/* (non-Javadoc)
	 * @see com.ibus.navigation.map.db.IMapDBLoader#getStops(java.lang.String)
	 * 
	 * The order of nodes in the map is: natural sort of stops is (lat_lon)+natural order of lineIds in the stop
	 */
	@Override
	public Stop[] getStops(String submap) {
		//stops ordered by stopid (lat long)
		String query = "select * from "+STATIONS_DETAILS+" where "+SUBMAP_ATTR+" = '"+submap+"' intersection itemName() is not null order by itemName() ASC";
		List<Item> items = getAllItems(query);
		Stop[] stations = new Stop[items.size()];
		int i = 0;
		int index = 0;
		for(Item itm:items){
			stations[i] = toStop(itm.getAttributes(), index);
			index+=stations[i].getNodes().size();
			i++;
		}
		return stations;
	}

	 /* 
	 * Retrieves just the segments (stops without nodes)
	 */
	public LineSegment[] getLineSegments(String submap) {
		String query = "select * from "+LINE_SEGMENTS+" where "+SUBMAP_ATTR+" = '"+submap+"'"; 
		List<Item> items = getAllItems(query);
		ArrayList<LineSegment> segments = new ArrayList<LineSegment>();
		LinkedList<Future<LineSegment>> fList = new LinkedList<Future<LineSegment>>();
		for(Item itm:items){
			for(final Attribute attr:itm.getAttributes()){
				if(attr.getName().equalsIgnoreCase(LINE_NAME_ATTR) ||
				   attr.getName().equalsIgnoreCase(SUBMAP_ATTR)){
					continue;
				}
				
				Future<LineSegment> submit = exec.submit(new Callable<LineSegment>() {
					@Override
					public LineSegment call() throws Exception {
						final LineSegment ls = gson.fromJson(attr.getValue(), LineSegment.class); 
						//query for the segment points
						String query = 	"select * from "+SEGMENT_POINTS+" where itemName() = '"
							+ls.getLineId()+"_"+attr.getName()+ "' order by itemName() Asc";
						List<Item> items = getAllItems(query);
						for (Item pointsitm : items) {
							TimedPoint[] tmp = new TimedPoint[pointsitm.getAttributes().size()];
							for (Attribute p : pointsitm.getAttributes()) {
								int indx = Integer.valueOf(p.getName());
								tmp[indx] = gson.fromJson(p.getValue(),	TimedPoint.class);
							}
							ls.setPoints(new ArrayList<TimedPoint>(Arrays.asList(tmp)));
						}
						return ls;
					}
				});
				fList.add(submit);
			}
		}
		
		for(Future<LineSegment> fls:fList){
			try {
				LineSegment ls = fls.get();
				segments.add(ls);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return segments.toArray(new LineSegment[0]);
	}
}
