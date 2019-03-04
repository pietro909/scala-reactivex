# Task

## Description

Given a webpage's address, look for links in that page. Repeat the process
for each of the links up to an arbitrary depth.
The final result must be an unsorted list of all the links found.

> Write an actor system which given an URL will recursively download the content,
extract links and follow them, bounded by a maximum depth; all links
encountered shall be returned.

## Actors

### The _Receptionist_

    * accepts a valid http URL as a message
    * process one request at a time
    * if another URL is being processed, cache the request
    * one a request has been fulfilled, process the next one

### The _Controller_

    * accepts a valid http URL as a message
    * tracks the already visited URLs
    * tracks the spawned actors
    * checks that the depth is still valid
    * creates an actor to visit that `url`
    * accepts a message containing a new link found in the page by the
      getter actor
    * for every new links, spawn a new getter actor
    * when all the actors are done, sends back the list of links
    
    
### The _Getter_

    * accepts an url and a depth parameter
    * downloads the page and looks for links
    * for each link, sends back the link and its depth

## Services

### WebClient

    * given a url, downloads its content
    * it's asyncronous (using `Future`)