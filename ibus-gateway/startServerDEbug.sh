jruby -J-Xdebug -J-Xrunjdwp:transport=dt_socket,server=y,address=3232,suspend=$1 script/rails server
