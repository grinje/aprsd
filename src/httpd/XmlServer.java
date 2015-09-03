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



public class XmlServer extends ServerBase
{
   private String _icon; 
   private String _adminuser, _updateusers;
   private int _seq = 0;
   
   public XmlServer(ServerAPI api) throws IOException
   {
      super(api);
      _icon = api.getProperty("map.icon.default", "sym.gif");
      
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
        String filt = _infraonly ? "infra" : req.getParameter("filter");
        RuleSet vfilt = ViewFilter.getFilter(filt, loggedIn);
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
             
        /* XML header with meta information */           
        out.println("<overlay seq=\""+_seq+"\"" +
            (filt==null ? ""  : " view=\"" + filt + "\"") + ">");
            
        printXmlMetaTags(out, req, loggedIn);
        out.println("<meta name=\"clientses\" value=\""+ client + "\"/>");    
        
        /* Could we put metadata in a separate service? */
        if (parms.get("metaonly") != null) {
             out.println("<meta name=\"metaonly\" value=\"true\"/>");
             out.println("</overlay>");              
             out.close();
        }
        out.println("<meta name=\"metaonly\" value=\"false\"/>");
        
        
         
        /* Output signs. A sign is not an APRS object
         * just a small icon and a title. It may be a better idea to do this
         * in map-layers instead?
         */
        int i=0;
        for (Signs.Item s: Signs.search(scale, uleft, lright))
        {
            LatLng ref = s.getPosition().toLatLng(); 
            if (ref == null)
                continue;
            String href = s.getUrl() == null ? "" : "href=\"" + s.getUrl() + "\"";
            String title = s.getDescr() == null ? "" : "title=\"" + fixText(s.getDescr()) + "\"";
            String icon = _wfiledir + "/icons/"+ s.getIcon();    
           
            out.println("<point id=\""+ (s.getId() < 0 ? "__sign" + (i++) : "__"+s.getId()) + "\" x=\""
                         + roundDeg(ref.getLng()) + "\" y=\"" + roundDeg(ref.getLat()) + "\" " 
                         + href + " " + title+">");
            out.println("   <icon src=\""+icon+"\"  w=\"22\" h=\"22\" ></icon>");     
            out.println("</point>");    
        }                
         
         
        
        /* Output APRS objects */
        for (TrackerPoint s: _api.getDB().search(uleft, lright)) 
        {
            Action action = vfilt.apply(s, scale); 
            // FIXME: Get CSS class from filter rules 
            
            if (s.getPosition() == null)
                continue; 
            if (s.getSource().isRestricted() && !loggedIn)
                continue;
            if (action.hideAll())
                continue;
                   
            LatLng ref = s.getPosition().toLatLng(); 
            if (ref == null) continue; 
            
            if (!s.visible() || (_api.getSar() != null && !loggedIn && _api.getSar().filter(s)))  
                   out.println("<delete id=\""+fixText(s.getIdent())+"\"/>");
            else {
               synchronized(s) {
                  ref = s.getPosition().toLatLng(); 
                  if (ref == null) continue; 
                  
                  String title = s.getDescr() == null ? "" 
                             : " title=\"" + fixText(s.getDescr()) + "\"";
                  String flags = " flags=\""+
                       (s instanceof AprsPoint ? "a":"") + 
                       (s instanceof AprsPoint && ((AprsPoint)s).isInfra() ? "i" : "") + "\"";

                  String icon = _wfiledir + "/icons/"+ (s.getIcon(showSarInfo) != null ? s.getIcon(showSarInfo) : _icon);    
                
                  out.println("<point id=\""+fixText(s.getIdent())+"\" x=\""
                               + roundDeg(ref.getLng()) + "\" y=\"" + roundDeg(ref.getLat()) + "\"" 
                               + title + flags + (s.isChanging() ? " redraw=\"true\"" : "") +
                               ((s instanceof AprsObject) && _api.getDB().getOwnObjects().hasObject(s.getIdent().replaceFirst("@.*",""))  ? " own=\"true\"":"") +">");
                  out.println("   <icon src=\""+icon+"\"  w=\"22\" h=\"22\" ></icon>");     
        
                   
                  /* Show label */ 
                  if (!action.hideIdent() && !s.isLabelHidden() ) {
                     String style = (!(s.getTrail().isEmpty()) ? "lmoving" : "lstill");
                     if (s instanceof AprsObject)
                        style = "lobject"; 
                     if (s instanceof AprsPoint && ((AprsPoint)s).isEmergency())
                        style += " lflash";
                     style += " "+ action.getStyle();
                     
                     out.println("   <label style=\""+style+"\">");
                     out.println("       "+fixText(s.getDisplayId(showSarInfo)));
                     out.println("   </label>"); 
                  }
                  
                  /* Trail */
                  Trail h = s.getTrail();
                  if (!action.hideTrail() && !h.isEmpty())
                     printTrailXml(out, s.getTrailColor(), s.getPosition(), h, uleft, lright); 
       
               } /* synchronized(s) */
               
               if (action.showPath() && s instanceof AprsPoint && ((AprsPoint)s).isInfra())
                  printPathXml(out, (Station) s, uleft, lright);              
               out.println("</point>");
            }
          
            /* Allow other threads to run */ 
            Thread.currentThread().yield ();
        }        
        out.println("</overlay>");
        out.close();
   }

     

   
}
