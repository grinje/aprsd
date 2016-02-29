/* 
 * Copyright (C) 2015 by LA7ECA, Øyvind Hanssen (ohanssen@acm.org)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
 
package no.polaric.aprsd.http;
import no.polaric.aprsd.*;
import no.polaric.aprsd.filter.*;

import org.simpleframework.http.core.Container;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.simpleframework.http.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.io.PrintStream;

import uk.me.jstott.jcoord.*;
import java.util.*;
import java.io.*;
import java.text.*;
import com.mindprod.base64.Base64;
import java.util.concurrent.locks.*; 



public class XmlServices extends ServerBase
{
   private String _adminuser, _updateusers;
   private int _seq = 0;
   
   public XmlServices(ServerAPI api) throws IOException
   {
      super(api);
      
      int trailage = api.getIntProperty("map.trail.maxAge", 15);
      Trail.setMaxAge(trailage * 60 * 1000); 
      int trailpause = api.getIntProperty("map.trail.maxPause", 10);
      Trail.setMaxPause(trailpause * 60 * 1000);
      int trailage_ext = api.getIntProperty("map.trail.maxAge.extended", 30);
      Trail.setMaxAge_Ext(trailage_ext * 60 * 1000); 
      int trailpause_ext = api.getIntProperty("map.trail.maxPause.extended", 20);
      Trail.setMaxPause_Ext(trailpause_ext * 60 * 1000);
   }


   
   /**
    * Look up a station/object and return id, x and y coordinates (separated by commas).
    * If not found, return nothing.
    */
   public void handle_finditem(Request req, Response res)
       throws IOException
   { 
       PrintWriter out = getWriter(res);
       String ident = req.getParameter("id").toUpperCase();

       Query parms = req.getQuery();
       TrackerPoint s = _api.getDB().getItem(ident, null);
       if (s==null) {
          int i = ident.lastIndexOf('-');
          if (i > -1)    
             ident = ident.substring(0, i);
          List<TrackerPoint> l = _api.getDB().getAllPrefix(ident);
          if (l.size() > 0)
              s = l.get(0);
       }
       if (s!=null && !s.expired() && s.getPosition() != null) {
          LatLng xpos = s.getPosition().toLatLng(); 
          out.println(s.getIdent()+"," + roundDeg(xpos.getLng()) + "," + roundDeg(xpos.getLat()));   
       }
       res.setValue("Content-Type", "text/csv; charset=utf-8");
       out.close();
   }
   


   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    * Secure version. Assume that this is is called only when user is logged in and
    * authenticated by frontend webserver.
    */
   public void handle_mapdata_sec(Request req, Response res) 
       throws IOException
   {
      /* FIXME: All that are logged in are allowed to see SAR info. Is this too liberal? */
      _handle_mapdata(req, res, true, true);
   }
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public void handle_mapdata(Request req, Response res)
      throws IOException
   {
      _handle_mapdata(req, res,  _api.getSar() == null || !_api.getSar().isAliasHidden(), false );
   }
   
   
   
   /**
    * Produces XML (Ka-map overlay spec.) for plotting station/symbols/labels on map.   
    */
   public void _handle_mapdata(Request req, Response res, boolean showSarInfo, boolean loggedIn) 
      throws IOException
   {         
        PrintWriter out = getWriter(res);
        String filt = req.getParameter("filter");
 //       RuleSet vfilt = ViewFilter.getFilter(filt, loggedIn);
        res.setValue("Content-Type", "text/xml; charset=utf-8");
        Query parms = req.getQuery();
        
        LatLng uleft = null, lright = null;
        if (parms.get("x1") != null) {
           Double x1 = Double.parseDouble( parms.get("x1") );
           Double x2 = Double.parseDouble( parms.get("x2") );
           Double x3 = Double.parseDouble( parms.get("x3") );    
           Double x4 = Double.parseDouble( parms.get("x4") );
           uleft  = new LatLng((double) x4, (double) x1); 
           lright = new LatLng((double) x2, (double) x3);
        }
        
        long scale = 0;
        if (parms.get("scale") != null)
           scale = Long.parseLong(parms.get("scale"));
        
        /* Sequence number at the time of request */
        int seq  = 0;
        synchronized (this) {
          _seq = (_seq+1) % 32000;
          seq = _seq;
        }
        long client = getSession(req);
        
        /* If requested, wait for a state change (see Notifier.java) */
        if (parms.get("wait") != null) 
            if (! Station.waitChange(uleft, lright, client) ) {
                out.println("<overlay cancel=\"true\"/>");             
                out.close();
                return;
            }
        
        printOverlay( 
           getMetaTags(req, client, loggedIn),
           out, seq, filt, scale, uleft, lright, 
           loggedIn, parms.get("metaonly") != null, showSarInfo
        );
        
        out.close();
   }

     

   
}
